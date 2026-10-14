package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import com.bkanent.rag.index.*;
import com.bkanent.rag.retrieval.RecallHit;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.param.*;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.QueryResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Migrated Spring AI execution path. SDK use is restricted to readonly schema/actual-state inspection. */
@Component
public class SpringAiMilvusDocumentStore implements IndexSink {
    private static final Logger log = LoggerFactory.getLogger(SpringAiMilvusDocumentStore.class);
    private final RagSpaceRegistry registry;
    private final MilvusServiceClient client;
    private final EmbeddingModel embeddingModel;
    private final Map<SchemaIdentity, MilvusVectorStore> stores = new ConcurrentHashMap<>();
    private final Map<String, SchemaIdentity> physicalSchemas = new ConcurrentHashMap<>();

    public SpringAiMilvusDocumentStore(RagSpaceRegistry registry, MilvusServiceClient client, EmbeddingModel embeddingModel) {
        this.registry = registry;
        this.client = client;
        this.embeddingModel = embeddingModel;
    }
    @Override public Backend backend() { return Backend.VECTOR; }

    @Override public void upsert(IndexDocument document) {
        RagSpaceDefinition space = registry.requireReady(document.spaceId());
        Map<String, Object> metadata = ProjectionCodec.metadata(document, space);
        MilvusVectorStore store = store(space);
        String pk = ProjectionCodec.physicalId(document.documentId());
        // 1.1.2 doAdd inserts; delete+add is intentionally ledger-protected eventual replacement.
        store.delete(List.of(pk));
        store.add(List.of(Document.builder().id(pk).text(document.document().content()).metadata(metadata).build()));
    }

    @Override public void delete(IndexDocument tombstone) {
        store(registry.requireReady(tombstone.spaceId())).delete(List.of(ProjectionCodec.physicalId(tombstone.documentId())));
    }

    public List<RecallHit> search(RagSpaceDefinition space, String query, List<RagFilter> filters, int limit) {
        SearchRequest request = SearchRequest.builder().query(query).topK(limit).similarityThresholdAll()
                .filterExpression(ProjectionFilters.vector(filters)).build();
        return store(space).similaritySearch(request).stream()
                .map(d -> ProjectionCodec.hit(d.getId(), d.getText(), d.getMetadata(), d.getScore(), RagRecallMode.VECTOR, space))
                .filter(hit -> ProjectionFilters.matches(hit.candidate().metadata(), filters)).toList();
    }

    /** Only a separately authorized provision operation calls this; search/upsert never initializes. */
    public void provision(String spaceId) {
        RagSpaceDefinition space = registry.require(spaceId);
        bind(space);
        MilvusVectorStore initializing = build(space, true);
        try { initializing.afterPropertiesSet(); }
        catch (RagException failure) { throw failure; }
        catch (Exception failure) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
        verifySchema(space);
    }

    public void verifySchema(RagSpaceDefinition space) {
        bind(space);
        var schema = space.vectorSchema();
        long deadline = com.bkanent.rag.security.http.RagHttpDeadline.capture(java.time.Duration.ofMillis(
                Math.min(5000, space.limits().recallTimeoutMillis())));
        // Configure retry first: SDK 2.5.8 withRetry copies base stubs, so applying it after withTimeout loses the interceptor.
        // Use a fresh RetryParam: withRetry(int) mutates the shared original parameter in this SDK version.
        var inspectionClient = client.withRetry(RetryParam.newBuilder().withMaxRetryTimes(1).build())
                .withTimeout(com.bkanent.rag.security.http.RagHttpDeadline.remainingNanos(deadline), java.util.concurrent.TimeUnit.NANOSECONDS);
        com.bkanent.rag.security.http.RagHttpDeadline.remainingNanos(deadline);
        var response = inspectionClient.describeCollection(DescribeCollectionParam.newBuilder()
                .withDatabaseName(schema.database()).withCollectionName(schema.collection()).build());
        checkSchemaDescribeResponse(response);
        DescCollResponseWrapper wrapper = new DescCollResponseWrapper(response.getData());
        var id = wrapper.getFieldByName(schema.idField());
        var vector = wrapper.getFieldByName(schema.vectorField());
        var content = wrapper.getFieldByName(schema.contentField());
        var metadata = wrapper.getFieldByName(schema.metadataField());
        if (id == null || !id.isPrimaryKey() || id.isAutoID() || id.getDataType() != DataType.VarChar
                || id.getMaxLength() < 36 || vector == null || vector.getDataType() != DataType.FloatVector
                || vector.getDimension() != schema.dimension() || content == null || content.getDataType() != DataType.VarChar
                || metadata == null || metadata.getDataType() != DataType.JSON) {
            throw new RagException(RagErrorCode.SPACE_NOT_READY, "vectorSchema");
        }
    }

    @Override public Optional<StoredRevision> inspect(String spaceId, String documentId) {
        RagSpaceDefinition space = registry.require(spaceId);
        bind(space);
        var schema = space.vectorSchema();
        // Both field and UUID are internal, validated values; no caller-native expression is accepted.
        var response = client.query(QueryParam.newBuilder().withDatabaseName(schema.database())
                .withCollectionName(schema.collection()).withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withExpr(schema.idField() + " == \"" + ProjectionCodec.physicalId(documentId) + "\"")
                .withOutFields(List.of(schema.metadataField())).withLimit(2L).build());
        checkResponse(response);
        var rows = new QueryResultsWrapper(response.getData()).getRowRecords();
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size() != 1) throw new RagException(RagErrorCode.INDEX_CONFLICT);
        JsonObject metadata = (JsonObject) rows.get(0).get(schema.metadataField());
        if (metadata == null || !metadata.has("projectionVersion") || !metadata.has("contentHash")) unavailable();
        return Optional.of(new StoredRevision(metadata.get("projectionVersion").getAsLong(),
                metadata.get("contentHash").getAsString(), false));
    }

    MilvusVectorStore store(RagSpaceDefinition space) {
        SchemaIdentity identity = bind(space);
        return stores.computeIfAbsent(identity, ignored -> { verifySchema(space); return build(space, false); });
    }

    private SchemaIdentity bind(RagSpaceDefinition space) {
        var v = space.vectorSchema();
        for (String name : List.of(v.idField(), v.contentField(), v.metadataField(), v.vectorField(), v.sourceTypeField(), v.sourceIdField())) {
            if (!name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new RagException(RagErrorCode.SPACE_NOT_READY);
        }
        if (!"metadata".equals(v.metadataField()) || v.dimension() <= 0) throw new RagException(RagErrorCode.SPACE_NOT_READY);
        SchemaIdentity identity = new SchemaIdentity(space.spaceId(), space.schemaVersion(), v);
        SchemaIdentity previous = physicalSchemas.putIfAbsent(v.database() + "\u0000" + v.collection(), identity);
        if (previous != null && !previous.equals(identity)) throw new RagException(RagErrorCode.SPACE_NOT_READY, "schemaIdentity");
        return identity;
    }

    private MilvusVectorStore build(RagSpaceDefinition space, boolean initialize) {
        var v = space.vectorSchema();
        return MilvusVectorStore.builder(client, new DimensionCheckedEmbeddingModel(embeddingModel, v.dimension()))
                .databaseName(v.database()).collectionName(v.collection()).embeddingDimension(v.dimension())
                .metricType(MetricType.valueOf(v.metricType())).indexType(IndexType.valueOf(v.indexType()))
                .indexParameters(v.indexParameters()).initializeSchema(initialize).autoId(false)
                .iDFieldName(v.idField()).contentFieldName(v.contentField()).metadataFieldName(v.metadataField())
                .embeddingFieldName(v.vectorField()).build();
    }
    private static void checkSchemaDescribeResponse(R<?> response) {
        try { checkResponse(response, true); }
        catch (RagException failure) {
            logSchemaDescribeFailure(response);
            throw failure;
        }
    }

    private static void checkResponse(R<?> response) {
        checkResponse(response, false);
    }

    private static void checkResponse(R<?> response, boolean schemaDescribe) {
        if (response != null && Objects.equals(response.getStatus(), R.Status.PermissionDenied.getCode())) {
            throw new RagException(RagErrorCode.FORBIDDEN);
        }
        RagException ragFailure = null;
        boolean collectionMissing = false;
        for (Throwable cause = response == null ? null : response.getException(); cause != null; cause = cause.getCause()) {
            if (cause instanceof RagException error) {
                if (!schemaDescribe || error.code() == RagErrorCode.AUTHENTICATION_FAILED
                        || error.code() == RagErrorCode.FORBIDDEN || error.code() == RagErrorCode.TIMEOUT) throw error;
                if (ragFailure == null) ragFailure = error;
            }
            if (cause instanceof io.grpc.StatusRuntimeException || cause instanceof io.grpc.StatusException) {
                io.grpc.Status.Code code = io.grpc.Status.fromThrowable(cause).getCode();
                if (code == io.grpc.Status.Code.UNAUTHENTICATED) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
                if (code == io.grpc.Status.Code.PERMISSION_DENIED) throw new RagException(RagErrorCode.FORBIDDEN);
                if (code == io.grpc.Status.Code.DEADLINE_EXCEEDED) throw new RagException(RagErrorCode.TIMEOUT);
            }
            if (cause instanceof io.milvus.exception.ServerException server) {
                if (server.getCompatibleCode() == io.milvus.grpc.ErrorCode.PermissionDenied) {
                    throw new RagException(RagErrorCode.FORBIDDEN);
                }
                // KI-104 / LR-36: only describe-schema compatibility enum proves this classification.
                // SDK 2.5.8 getStatus() merges modern code and legacy error_code; numeric 100 is not a predicate.
                collectionMissing |= schemaDescribe
                        && server.getCompatibleCode() == io.milvus.grpc.ErrorCode.CollectionNotExists;
            }
        }
        // Scan the entire cause chain for hard failures before applying the schema-only missing classification.
        if (ragFailure != null) throw ragFailure;
        if (collectionMissing) throw new RagException(RagErrorCode.SPACE_NOT_READY, "vectorSchema");
        if (response == null || response.getException() != null || response.getData() == null
                || !Objects.equals(response.getStatus(), R.Status.Success.getCode())) unavailable();
    }

    private static void logSchemaDescribeFailure(R<?> response) {
        Integer serverStatus = null;
        String compatibleCode = null;
        String grpcCode = null;
        boolean serverFound = false;
        for (Throwable cause = response == null ? null : response.getException(); cause != null; cause = cause.getCause()) {
            if (cause instanceof io.milvus.exception.ServerException server && !serverFound) {
                serverFound = true;
                serverStatus = server.getStatus();
                compatibleCode = server.getCompatibleCode() == null ? null : server.getCompatibleCode().name();
            }
            if (grpcCode == null && (cause instanceof io.grpc.StatusRuntimeException || cause instanceof io.grpc.StatusException)) {
                grpcCode = io.grpc.Status.fromThrowable(cause).getCode().name();
            }
        }
        // Fixed safe scalar fields only: never pass response, throwable, reason or connection identity to the logger.
        log.warn("operation=SCHEMA_DESCRIBE rStatus={} serverStatus={} compatibleCode={} grpcCode={}",
                response == null ? null : response.getStatus(), serverStatus, compatibleCode, grpcCode);
    }
    private record SchemaIdentity(String spaceId, String schemaVersion, RagSpaceDefinition.VectorSchema vectorSchema) { }
    private static void unavailable() { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
}
