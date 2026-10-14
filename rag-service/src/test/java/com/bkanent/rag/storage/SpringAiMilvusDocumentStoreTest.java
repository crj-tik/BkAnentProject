package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import com.bkanent.rag.index.IndexDocument;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.milvus.client.MilvusServiceClient;
import io.milvus.exception.ServerException;
import io.milvus.grpc.ErrorCode;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.param.R;
import io.milvus.param.collection.DescribeCollectionParam;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.*;
import org.springframework.ai.vectorstore.milvus.MilvusFilterExpressionConverter;
import java.time.Instant;
import java.util.*;
import static com.bkanent.rag.retrieval.RetrievalTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SpringAiMilvusDocumentStoreTest {
    private static MilvusServiceClient inspectionClient() {
        var client = mock(MilvusServiceClient.class);
        when(client.withRetry(any(io.milvus.param.RetryParam.class))).thenReturn(client);
        when(client.withTimeout(anyLong(), any())).thenReturn(client);
        return client;
    }
    @Test void schemaInspectionUsesFreshSingleAttemptAndRemainingDeadline() {
        var client = inspectionClient();
        when(client.describeCollection(any())).thenReturn(R.failed(io.grpc.Status.DEADLINE_EXCEEDED.asRuntimeException()));
        var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
        try (var ignored = com.bkanent.rag.security.http.RagHttpDeadline.open(java.time.Duration.ofMillis(90))) {
            assertEquals(RagErrorCode.TIMEOUT, assertThrows(RagException.class, () -> store.verifySchema(space())).code());
        }
        var retry = org.mockito.ArgumentCaptor.forClass(io.milvus.param.RetryParam.class);
        var timeout = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(client).withRetry(retry.capture());
        verify(client).withTimeout(timeout.capture(), eq(java.util.concurrent.TimeUnit.NANOSECONDS));
        assertEquals(1, retry.getValue().getMaxRetryTimes());
        assertTrue(timeout.getValue() > 0 && timeout.getValue() <= 90000000);
        verify(client, times(1)).describeCollection(any());
    }
    @Test void dimensionWrapperChecksActualVectorsWithoutCreatingAnHttpClient() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(Document.class))).thenReturn(new float[] {1, 2});
        var checked = new DimensionCheckedEmbeddingModel(model, 3);
        assertEquals(RagErrorCode.SPACE_NOT_READY,
                assertThrows(RagException.class, () -> checked.embed(new Document("text"))).code());
        when(model.embed(any(Document.class))).thenReturn(new float[] {1, Float.NaN, 3});
        assertThrows(RagException.class, () -> checked.embed(new Document("text")));
        when(model.embed(any(Document.class))).thenReturn(new float[] {1, 2, 3});
        assertArrayEquals(new float[] {1, 2, 3}, checked.embed(new Document("text")));
        assertEquals(3, checked.dimensions());
        verify(model, times(3)).embed(any(Document.class));
    }
    @Test void actualSchemaIsInspectedAndNeverInitializedBySearch() {
        MilvusServiceClient client = inspectionClient();
        when(client.describeCollection(any(DescribeCollectionParam.class))).thenReturn(R.success(DescribeCollectionResponse.getDefaultInstance()));
        var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
        assertThrows(RagException.class, () -> store.search(space(), "query", List.of(), 5));
        var order = inOrder(client);
        order.verify(client).withRetry(any(io.milvus.param.RetryParam.class));
        order.verify(client).withTimeout(anyLong(), eq(java.util.concurrent.TimeUnit.NANOSECONDS));
        order.verify(client).describeCollection(any(DescribeCollectionParam.class));
        verifyNoMoreInteractions(client);
    }
    @Test void readonlySchemaAndRevisionInspectionPreserveGrpcPermissionFailures() {
        for (io.grpc.Status denied : List.of(io.grpc.Status.UNAUTHENTICATED, io.grpc.Status.PERMISSION_DENIED)) {
            MilvusServiceClient client = inspectionClient();
            when(client.describeCollection(any(DescribeCollectionParam.class))).thenReturn(R.failed(denied.asRuntimeException()));
            when(client.query(any(io.milvus.param.dml.QueryParam.class))).thenReturn(R.failed(denied.asRuntimeException()));
            var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
            RagErrorCode code = denied.getCode() == io.grpc.Status.Code.UNAUTHENTICATED
                    ? RagErrorCode.AUTHENTICATION_FAILED : RagErrorCode.FORBIDDEN;
            assertEquals(code, assertThrows(RagException.class, () -> store.verifySchema(space())).code());
            assertEquals(code, assertThrows(RagException.class, () -> store.inspect(space().spaceId(), "listing:1")).code());
        }
        MilvusServiceClient client = inspectionClient();
        when(client.describeCollection(any(DescribeCollectionParam.class))).thenReturn(R.failed(R.Status.PermissionDenied, "denied"));
        var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> store.verifySchema(space())).code());
    }
    @Test void onlyOfficialCompatibleMissingCollectionMapsSchemaToNotReady() {
        for (int status : List.of(4, 100, 987654)) {
            var client = inspectionClient();
            var failure = new ServerException("provider text must not classify", status, ErrorCode.CollectionNotExists);
            R<DescribeCollectionResponse> response = R.failed(failure);
            assertSame(failure, response.getException());
            assertEquals(Integer.valueOf(status), response.getStatus());
            when(client.describeCollection(any())).thenReturn(response);
            when(client.query(any(io.milvus.param.dml.QueryParam.class))).thenReturn(R.failed(failure));
            var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
            var schemaFailure = assertThrows(RagException.class, () -> store.verifySchema(space()));
            assertEquals(RagErrorCode.SPACE_NOT_READY, schemaFailure.code());
            assertEquals("vectorSchema", schemaFailure.field());
            var queryFailure = assertThrows(RagException.class, () -> store.inspect(space().spaceId(), "listing:1"));
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, queryFailure.code());
            assertNull(queryFailure.field());
        }
    }

    @Test void officialModernDescribeTupleMapsOnlySchemaToNotReady() {
        for (boolean wrapped : List.of(false, true)) {
            var client = inspectionClient();
            var modern = new ServerException("provider text must not classify", 100, ErrorCode.UnexpectedError);
            Exception failure = wrapped ? new IllegalStateException("provider wrapper", modern) : modern;
            R<DescribeCollectionResponse> response = R.failed(failure);
            assertSame(failure, response.getException());
            assertEquals(Integer.valueOf(100), modern.getStatus());
            assertEquals(ErrorCode.UnexpectedError, modern.getCompatibleCode());
            when(client.describeCollection(any())).thenReturn(response);
            when(client.query(any(io.milvus.param.dml.QueryParam.class))).thenReturn(R.failed(failure));
            var embedding = mock(EmbeddingModel.class);
            var store = new SpringAiMilvusDocumentStore(registry(), client, embedding);
            var schemaFailure = assertThrows(RagException.class, () -> store.verifySchema(space()));
            assertEquals(RagErrorCode.SPACE_NOT_READY, schemaFailure.code());
            assertEquals("vectorSchema", schemaFailure.field());
            var queryFailure = assertThrows(RagException.class, () -> store.inspect(space().spaceId(), "listing:1"));
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, queryFailure.code());
            assertNull(queryFailure.field());
            verify(client).withRetry(any(io.milvus.param.RetryParam.class));
            verify(client).withTimeout(anyLong(), eq(java.util.concurrent.TimeUnit.NANOSECONDS));
            verify(client).describeCollection(any());
            verify(client).query(any(io.milvus.param.dml.QueryParam.class));
            verifyNoMoreInteractions(client);
            verifyNoInteractions(embedding);
        }
    }

    @Test void modernNumbersOtherNamespacesUnknownAndMissingMessagesRemainUnavailable() {
        List<R<DescribeCollectionResponse>> responses = new ArrayList<>();
        for (ErrorCode compatible : Arrays.asList(ErrorCode.DataCoordNA,
                ErrorCode.Success, ErrorCode.UNRECOGNIZED, null)) {
            responses.add(R.failed(new ServerException("collection not exist", 100, compatible)));
        }
        for (int status : List.of(1, 4, -3, 987654)) {
            responses.add(R.failed(new ServerException("collection not exist", status, ErrorCode.UnexpectedError)));
        }
        responses.add(R.failed(new ServerException("collection not exist", null, ErrorCode.UnexpectedError)));
        responses.add(R.failed(ErrorCode.DataCoordNA, "collection not exist"));
        responses.add(R.failed(new ServerException("collection not exist", null, null)));
        responses.add(R.failed(new ServerException("collection not exist", 28, ErrorCode.CollectionNameNotFound)));
        responses.add(R.failed(new ServerException("collection not exist", 987654, ErrorCode.UNRECOGNIZED)));
        responses.add(R.failed(new Exception("collection not exist code=100 error_code=CollectionNotExists")));
        responses.add(R.failed(ErrorCode.CollectionNotExists, "collection not exist"));
        responses.add(R.failed(R.Status.CollectionNotExists, "collection not exist"));
        responses.add(R.success());
        responses.add(null);
        for (R<DescribeCollectionResponse> response : responses) {
            var client = inspectionClient();
            when(client.describeCollection(any())).thenReturn(response);
            var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
            var failure = assertThrows(RagException.class, () -> store.verifySchema(space()));
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, failure.code());
            assertNull(failure.field());
        }
    }

    @Test void modernDescribeTupleNeverCombinesFieldsFromDifferentServerExceptions() {
        var codeOnly = new ServerException("provider text must not classify", 100, null);
        codeOnly.initCause(new ServerException("provider text must not classify", 1, ErrorCode.UnexpectedError));
        var enumOnly = new ServerException("provider text must not classify", 1, ErrorCode.UnexpectedError);
        enumOnly.initCause(new ServerException("provider text must not classify", 100, ErrorCode.DataCoordNA));
        for (ServerException failure : List.of(codeOnly, enumOnly)) {
            var client = inspectionClient();
            R<DescribeCollectionResponse> response = R.failed(failure);
            assertSame(failure, response.getException());
            when(client.describeCollection(any())).thenReturn(response);
            when(client.query(any(io.milvus.param.dml.QueryParam.class))).thenReturn(R.failed(failure));
            var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
            var schemaFailure = assertThrows(RagException.class, () -> store.verifySchema(space()));
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, schemaFailure.code());
            assertNull(schemaFailure.field());
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(RagException.class, () -> store.inspect(space().spaceId(), "listing:1")).code());
        }
    }

    @Test void schemaMissingDoesNotMaskNestedGrpcRagOrCompatiblePermissionHardFailures() {
        List<Exception> hardFailures = new ArrayList<>();
        List<RagErrorCode> expected = new ArrayList<>();
        for (io.grpc.Status status : List.of(io.grpc.Status.UNAUTHENTICATED,
                io.grpc.Status.PERMISSION_DENIED, io.grpc.Status.DEADLINE_EXCEEDED)) {
            RagErrorCode code = switch (status.getCode()) {
                case UNAUTHENTICATED -> RagErrorCode.AUTHENTICATION_FAILED;
                case PERMISSION_DENIED -> RagErrorCode.FORBIDDEN;
                default -> RagErrorCode.TIMEOUT;
            };
            hardFailures.add(status.asRuntimeException());
            expected.add(code);
            hardFailures.add(status.asException());
            expected.add(code);
        }
        hardFailures.add(new ServerException("provider text", 123456, ErrorCode.PermissionDenied));
        expected.add(RagErrorCode.FORBIDDEN);
        for (RagErrorCode code : List.of(RagErrorCode.AUTHENTICATION_FAILED, RagErrorCode.FORBIDDEN, RagErrorCode.TIMEOUT)) {
            hardFailures.add(new RagException(code));
            expected.add(code);
        }
        for (ErrorCode compatible : List.of(ErrorCode.CollectionNotExists, ErrorCode.UnexpectedError)) {
            for (int i = 0; i < hardFailures.size(); i++) {
                var missing = new ServerException("collection not exist", 100, compatible);
                missing.initCause(new IllegalStateException("nested provider text", hardFailures.get(i)));
                var client = inspectionClient();
                when(client.describeCollection(any())).thenReturn(R.failed(missing));
                var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
                var failure = assertThrows(RagException.class, () -> store.verifySchema(space()));
                assertEquals(expected.get(i), failure.code());
                assertNull(failure.field());
                if (hardFailures.get(i) instanceof RagException) assertSame(hardFailures.get(i), failure);
            }
        }
        var existing = new RagException(RagErrorCode.INDEX_CONFLICT);
        existing.initCause(io.grpc.Status.UNAUTHENTICATED.asRuntimeException());
        var client = inspectionClient();
        when(client.describeCollection(any())).thenReturn(R.failed(existing));
        var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED,
                assertThrows(RagException.class, () -> store.verifySchema(space())).code());
    }

    @Test void schemaDescribePreservesExistingRagFailuresAndCompatiblePermission() {
        var client = inspectionClient();
        for (boolean ragOutside : List.of(false, true)) {
            var preserved = new RagException(RagErrorCode.INDEX_CONFLICT);
            var modern = new ServerException("provider text must not classify", 100, ErrorCode.UnexpectedError);
            if (ragOutside) preserved.initCause(modern);
            else modern.initCause(new IllegalStateException("provider wrapper", preserved));
            Exception failure = ragOutside ? preserved : modern;
            when(client.describeCollection(any())).thenReturn(R.failed(failure));
            when(client.query(any(io.milvus.param.dml.QueryParam.class))).thenReturn(R.failed(failure));
            var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
            assertSame(preserved, assertThrows(RagException.class, () -> store.verifySchema(space())));
            assertSame(preserved, assertThrows(RagException.class, () -> store.inspect(space().spaceId(), "listing:1")));
        }
        var existing = new RagException(RagErrorCode.INDEX_CONFLICT);
        when(client.describeCollection(any())).thenReturn(R.failed(existing));
        var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
        assertSame(existing, assertThrows(RagException.class, () -> store.verifySchema(space())));
        when(client.describeCollection(any())).thenReturn(R.failed(
                new ServerException("collection not exist", 100, ErrorCode.PermissionDenied)));
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> store.verifySchema(space())).code());
    }

    @Test void failedSchemaSearchRemainsReadonlySingleAttemptAndDoesNotCacheOrEmbed() {
        var client = inspectionClient();
        when(client.describeCollection(any())).thenReturn(R.failed(
                new ServerException("provider text", 100, ErrorCode.CollectionNotExists)));
        var embedding = mock(EmbeddingModel.class);
        var store = new SpringAiMilvusDocumentStore(registry(), client, embedding);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(RagErrorCode.SPACE_NOT_READY,
                    assertThrows(RagException.class, () -> store.search(space(), "query", List.of(), 5)).code());
        }
        var retry = org.mockito.ArgumentCaptor.forClass(io.milvus.param.RetryParam.class);
        var timeout = org.mockito.ArgumentCaptor.forClass(Long.class);
        var order = inOrder(client);
        for (int attempt = 0; attempt < 2; attempt++) {
            order.verify(client).withRetry(retry.capture());
            order.verify(client).withTimeout(timeout.capture(), eq(java.util.concurrent.TimeUnit.NANOSECONDS));
            order.verify(client).describeCollection(any());
        }
        assertTrue(retry.getAllValues().stream().allMatch(value -> value.getMaxRetryTimes() == 1));
        assertNotSame(retry.getAllValues().get(0), retry.getAllValues().get(1));
        assertTrue(timeout.getAllValues().stream().allMatch(value -> value > 0 && value <= 5000000000L));
        verifyNoMoreInteractions(client);
        verifyNoInteractions(embedding);
    }

    @Test void schemaFailureDiagnosticsContainOnlyFixedSafeScalarsAndNoThrowable() {
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(SpringAiMilvusDocumentStore.class);
        Level previousLevel = logger.getLevel();
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            var client = inspectionClient();
            var missing = new ServerException("SECRET_REASON endpoint=http://secret db=private collection=private token=secret\nSTACK",
                    100, ErrorCode.CollectionNotExists);
            missing.initCause(io.grpc.Status.PERMISSION_DENIED.withDescription("SECRET_GRPC token=secret").asRuntimeException());
            when(client.describeCollection(any())).thenReturn(R.failed(missing));
            when(client.query(any(io.milvus.param.dml.QueryParam.class))).thenReturn(R.failed(missing));
            var store = new SpringAiMilvusDocumentStore(registry(), client, mock(EmbeddingModel.class));
            assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class, () -> store.verifySchema(space())).code());
            assertEquals(1, appender.list.size());
            ILoggingEvent event = appender.list.get(0);
            assertEquals("operation=SCHEMA_DESCRIBE rStatus=100 serverStatus=100 compatibleCode=CollectionNotExists grpcCode=PERMISSION_DENIED",
                    event.getFormattedMessage());
            assertNull(event.getThrowableProxy());
            for (Object argument : event.getArgumentArray()) {
                assertTrue(argument == null || argument instanceof Integer || argument instanceof String);
            }
            assertEquals(RagErrorCode.FORBIDDEN,
                    assertThrows(RagException.class, () -> store.inspect(space().spaceId(), "listing:1")).code());
            assertEquals(1, appender.list.size());
            when(client.describeCollection(any())).thenReturn(null);
            assertThrows(RagException.class, () -> store.verifySchema(space()));
            assertEquals("operation=SCHEMA_DESCRIBE rStatus=null serverStatus=null compatibleCode=null grpcCode=null",
                    appender.list.get(1).getFormattedMessage());
            assertNull(appender.list.get(1).getThrowableProxy());
            when(client.describeCollection(any())).thenReturn(R.failed(new ServerException("SECRET_REASON", null, ErrorCode.UNRECOGNIZED)));
            assertThrows(RagException.class, () -> store.verifySchema(space()));
            assertEquals("operation=SCHEMA_DESCRIBE rStatus=null serverStatus=null compatibleCode=UNRECOGNIZED grpcCode=null",
                    appender.list.get(2).getFormattedMessage());
            assertNull(appender.list.get(2).getThrowableProxy());
            when(client.describeCollection(any())).thenReturn(R.success(DescribeCollectionResponse.getDefaultInstance()));
            assertThrows(Exception.class, () -> store.verifySchema(space()));
            assertEquals(3, appender.list.size());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test void projectionKeepsLogicalIdAndVersionAndNeverAllowsIdentityOverride() {
        var document = new RagDocument("listing", "1", "body", Map.of("area", RagValue.number(10)));
        String hash = RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, document);
        var event = new RagIndexEvent(1, "request", "event", "listing.knowledge", "listing:1", 3, "schema-1",
                RagIndexOperation.UPSERT, hash, document, Instant.now());
        Map<String, Object> metadata = ProjectionCodec.metadata(new IndexDocument(event, "payload"), space());
        assertEquals("listing:1", metadata.get("documentId"));
        assertEquals(3L, metadata.get("projectionVersion"));
        assertEquals(hash, metadata.get("contentHash"));
        assertEquals("listing", metadata.get("source_type"));
    }
    @Test void springAiWritesAndDeletesSafePhysicalIdsButPreservesLogicalMetadata() {
        var store = spy(new SpringAiMilvusDocumentStore(registry(), mock(MilvusServiceClient.class), mock(EmbeddingModel.class)));
        var vector = mock(org.springframework.ai.vectorstore.milvus.MilvusVectorStore.class);
        doReturn(vector).when(store).store(any(RagSpaceDefinition.class));
        var document = new RagDocument("listing", "1", "body", Map.of());
        var event = new RagIndexEvent(1, "request", "event", "listing.knowledge", "listing:1", 2, "schema-1",
                RagIndexOperation.UPSERT, RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, document), document, Instant.now());
        store.upsert(new IndexDocument(event, "payload"));
        @SuppressWarnings("unchecked") org.mockito.ArgumentCaptor<List<Document>> docs = org.mockito.ArgumentCaptor.forClass(List.class);
        var order = inOrder(vector);
        order.verify(vector).delete(List.of(ProjectionCodec.physicalId("listing:1")));
        order.verify(vector).add(docs.capture());
        assertEquals("listing:1", docs.getValue().get(0).getMetadata().get("documentId"));
        assertEquals(ProjectionCodec.physicalId("listing:1"), docs.getValue().get(0).getId());
        var deletion = new RagIndexEvent(1, "request", "event-delete", "listing.knowledge", "listing:1", 3, "schema-1",
                RagIndexOperation.DELETE, RagCanonicalHash.documentHash(RagIndexOperation.DELETE, null), null, Instant.now());
        store.delete(new IndexDocument(deletion, "payload"));
        verify(vector, times(2)).delete(List.of(ProjectionCodec.physicalId("listing:1")));
        verifyNoMoreInteractions(vector);
    }
    @Test void schemaCacheRefusesSamePhysicalCollectionWithDifferentIdentity() {
        var store = spy(new SpringAiMilvusDocumentStore(registry(), mock(MilvusServiceClient.class), mock(EmbeddingModel.class)));
        doNothing().when(store).verifySchema(any());
        assertSame(store.store(space()), store.store(space()));
        var old = space();
        var v = old.vectorSchema();
        var changed = new RagSpaceDefinition(old.spaceId(), old.ownerDomain(), old.policyVersion(), "schema-2", old.elasticsearchIndex(),
                new RagSpaceDefinition.VectorSchema(v.database(), v.collection(), "different-model", 4, v.idField(), v.contentField(),
                        v.metadataField(), v.vectorField(), v.sourceTypeField(), v.sourceIdField(), v.metricType(), v.indexType(), v.indexParameters()),
                old.fields(), old.returnFields(), old.searchRoles(), old.adminRoles(), old.allowedClients(), old.limits(), old.rerankProfile());
        assertEquals(RagErrorCode.SPACE_NOT_READY, assertThrows(RagException.class, () -> store.store(changed)).code());
    }
    @Test void realSpringAiConverterCannotBreakOutOfQuotedLiterals() {
        String value = "user\" || metadata[\"ownerId\"] != \"x\\";
        var filter = new RagFilter("ownerId", RagFilterOperator.EQ, RagValue.string(value));
        String expression = new MilvusFilterExpressionConverter().convertExpression(ProjectionFilters.vector(List.of(filter)));
        assertEquals("metadata[\"ownerId\"] == \"user\\\" || metadata[\\\"ownerId\\\"] != \\\"x\\\\\"", expression);
        var list = new RagFilter("resourceId", RagFilterOperator.IN, RagValue.strings(List.of(value)));
        String listExpression = new MilvusFilterExpressionConverter().convertExpression(ProjectionFilters.vector(List.of(list)));
        assertTrue(listExpression.contains("\\\"ownerId\\\""));
        assertTrue(listExpression.endsWith("\\\\\"]"));
        var control = new RagFilter("ownerId", RagFilterOperator.EQ, RagValue.string("a\nb\r\tc"));
        String escaped = new MilvusFilterExpressionConverter().convertExpression(ProjectionFilters.vector(List.of(control)));
        assertTrue(escaped.contains("a\\nb\\r\\tc"));
        assertFalse(escaped.contains("\n"));
        var denied = new RagFilter("ownerId", RagFilterOperator.EQ, RagValue.string("a\u0001b"));
        assertThrows(RagException.class, () -> ProjectionFilters.vector(List.of(denied)));
    }
}
