package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import com.bkanent.rag.index.IndexDocument;
import io.milvus.client.MilvusServiceClient;
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
