package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import com.bkanent.rag.index.IndexDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import java.time.Instant;
import java.util.*;
import static com.bkanent.rag.retrieval.RetrievalTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ElasticsearchProjectionStoreTest {
    @Test void writesCompleteProjectionAndExplicitMappingWithoutEntityOrRpc() throws Exception {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        var store = new ElasticsearchProjectionStore(registry(), operations, new ObjectMapper(), mock(ElasticsearchDeadlineReader.class));
        var document = new RagDocument("listing", "1", "body", Map.of("area", RagValue.number(10)));
        var event = new RagIndexEvent(1, "request", "event", "listing.knowledge", "listing:1", 3, "schema-1",
                RagIndexOperation.UPSERT, RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, document), document, Instant.now());
        store.upsert(new IndexDocument(event, "payload"));
        ArgumentCaptor<IndexQuery> query = ArgumentCaptor.forClass(IndexQuery.class);
        verify(operations).index(query.capture(), any(IndexCoordinates.class));
        var json = new ObjectMapper().readTree(query.getValue().getSource());
        assertEquals("listing:1", json.get("documentId").asText());
        assertEquals("body", json.get("content").asText());
        assertEquals(3, json.get("metadata").get("projectionVersion").asInt());
        assertFalse(json.has("_class"));
        Map<?, ?> mapping = store.mapping(space());
        assertEquals("strict", mapping.get("dynamic"));
        verifyNoMoreInteractions(operations);
    }
    @Test void memoryLastObservedAtHasExplicitNanosecondMappingAndRejectsMillisecondSchema() {
        var base = space();
        Map<String, RagSpaceDefinition.FilterField> fields = new HashMap<>(base.fields());
        fields.put("lastObservedAt", new RagSpaceDefinition.FilterField(RagValueType.INSTANT,
                Set.of(RagFilterOperator.GTE, RagFilterOperator.LTE), true));
        var memory = new RagSpaceDefinition("memory.preference", "memory", base.policyVersion(), base.schemaVersion(),
                "rag_memory_preference_v1", base.vectorSchema(), fields, Set.of("lastObservedAt"),
                base.searchRoles(), base.adminRoles(), base.allowedClients(), base.limits(), base.rerankProfile());
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations index = mock(IndexOperations.class);
        ElasticsearchDeadlineReader reader = mock(ElasticsearchDeadlineReader.class);
        when(operations.indexOps(IndexCoordinates.of(memory.elasticsearchIndex()))).thenReturn(index);
        when(index.exists()).thenReturn(true);
        var store = new ElasticsearchProjectionStore(registry(), operations, new ObjectMapper(), reader);
        Map<String, Object> mapping = store.mapping(memory);
        var json = new ObjectMapper().valueToTree(mapping);
        assertEquals("strict", json.path("dynamic").asText());
        assertEquals("strict", json.path("properties").path("metadata").path("dynamic").asText());
        assertEquals(Map.of("type", "date_nanos", "format", "strict_date_optional_time_nanos"),
                new ObjectMapper().convertValue(json.path("properties").path("metadata").path("properties").path("lastObservedAt"), Map.class));
        schemaResponses(reader, memory, mapping);
        assertDoesNotThrow(() -> store.inspectSchema(memory));
        for (Map<String, String> incompatible : List.of(
                Map.of("type", "date", "format", "strict_date_optional_time_nanos"),
                Map.of("type", "date_nanos", "format", "strict_date_optional_time"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> actual = new ObjectMapper().convertValue(mapping, Map.class);
            @SuppressWarnings("unchecked")
            var properties = (Map<String, Object>) actual.get("properties");
            @SuppressWarnings("unchecked")
            var metadata = (Map<String, Object>) properties.get("metadata");
            @SuppressWarnings("unchecked")
            var metadataFields = (Map<String, Object>) metadata.get("properties");
            metadataFields.put("lastObservedAt", incompatible);
            schemaResponses(reader, memory, actual);
            assertEquals(RagErrorCode.SPACE_NOT_READY,
                    assertThrows(RagException.class, () -> store.inspectSchema(memory)).code());
        }
        verify(index, never()).create(anyMap(), any());
        verify(index, never()).putMapping(any(org.springframework.data.elasticsearch.core.document.Document.class));
        verifyNoInteractions(index, operations);
    }
    private static void schemaResponses(ElasticsearchDeadlineReader reader, RagSpaceDefinition space, Map<String, Object> mapping) {
        try {
            var head = mock(org.elasticsearch.client.Response.class);
            when(head.getStatusLine()).thenReturn(new org.apache.http.message.BasicStatusLine(org.apache.http.HttpVersion.HTTP_1_1, 200, "OK"));
            var response = mock(org.elasticsearch.client.Response.class);
            when(response.getEntity()).thenReturn(new org.apache.http.entity.StringEntity(new ObjectMapper().writeValueAsString(
                    Map.of(space.elasticsearchIndex(), Map.of("mappings", mapping))), java.nio.charset.StandardCharsets.UTF_8));
            doAnswer(call -> call.<org.elasticsearch.client.Request>getArgument(0).getMethod().equals("HEAD") ? head : response)
                    .when(reader).read(any(), any(), anyInt());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    @Test void schemaHeadAndMappingShareOneAbsoluteDeadlineAndNeverUseSynchronousIndexOps() throws Exception {
        var client = mock(org.elasticsearch.client.RestClient.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var cancellation = mock(org.elasticsearch.client.Cancellable.class);
        when(client.performRequestAsync(any(), any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                Thread.sleep(80);
                var head = mock(org.elasticsearch.client.Response.class);
                when(head.getStatusLine()).thenReturn(new org.apache.http.message.BasicStatusLine(org.apache.http.HttpVersion.HTTP_1_1, 200, "OK"));
                call.<org.elasticsearch.client.ResponseListener>getArgument(1).onSuccess(head);
            }
            return cancellation;
        });
        var reader = new ElasticsearchDeadlineReader(client);
        var operations = mock(ElasticsearchOperations.class);
        var store = new ElasticsearchProjectionStore(registry(), operations, new ObjectMapper(), reader);
        try (var ignored = com.bkanent.rag.security.http.RagHttpDeadline.open(java.time.Duration.ofMillis(180))) {
            long start = System.nanoTime();
            assertEquals(RagErrorCode.TIMEOUT, assertThrows(RagException.class, () -> store.inspectSchema(space())).code());
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 700);
            assertEquals(2, calls.get());
            verify(cancellation, atLeastOnce()).cancel();
            verifyNoInteractions(operations);
        } finally { reader.close(); }
    }
    @Test void inspectRefusesWrongFieldMappingAndProvisionDoesNotOverwriteExistingMapping() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations index = mock(IndexOperations.class);
        when(operations.indexOps(any(IndexCoordinates.class))).thenReturn(index);
        when(index.exists()).thenReturn(true);
        var reader = mock(ElasticsearchDeadlineReader.class);
        schemaResponses(reader, space(), Map.of("properties", Map.of("content", Map.of("type", "keyword"))));
        var store = new ElasticsearchProjectionStore(registry(), operations, new ObjectMapper(), reader);
        assertEquals(RagErrorCode.SPACE_NOT_READY, assertThrows(RagException.class, () -> store.provision(space())).code());
        verify(index, never()).create(anyMap(), any());
        verify(index, never()).putMapping(any(org.springframework.data.elasticsearch.core.document.Document.class));
    }
}
