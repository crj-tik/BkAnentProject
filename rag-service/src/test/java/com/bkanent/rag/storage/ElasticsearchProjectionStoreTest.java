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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void provisionAcceptsImplicitObjectReadbackWithoutChangingExistingSchema(boolean exists) {
        var operations = mock(ElasticsearchOperations.class);
        var index = mock(IndexOperations.class);
        when(operations.indexOps(any(IndexCoordinates.class))).thenReturn(index);
        when(index.exists()).thenReturn(exists);
        if (!exists) when(index.create(anyMap(), any())).thenReturn(true);
        var reader = mock(ElasticsearchDeadlineReader.class);
        var store = new ElasticsearchProjectionStore(registry(), operations, new ObjectMapper(), reader);
        var actual = new ObjectMapper().valueToTree(store.mapping(space()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) actual.path("properties").path("metadata")).remove("type");
        schemaResponses(reader, space(), new ObjectMapper().convertValue(actual, Map.class));
        assertDoesNotThrow(() -> store.provision(space()));
        if (exists) verify(index, never()).create(anyMap(), any());
        else {
            var mapping = ArgumentCaptor.forClass(org.springframework.data.elasticsearch.core.document.Document.class);
            verify(index).create(eq(Map.of()), mapping.capture());
            assertEquals(store.mapping(space()), mapping.getValue());
        }
        verify(index, never()).putMapping(any(org.springframework.data.elasticsearch.core.document.Document.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "nullType", "nestedType", "keywordType", "missingProperties", "emptyProperties", "nonMapProperties",
            "missingMetadata", "missingDynamic", "booleanDynamic", "looseDynamic",
            "looseRootDynamic", "missingLeafType", "wrongLeafType", "wrongContentType"})
    void implicitObjectReadbackStillRejectsIncompatibleManagedSchema(String mismatch) {
        var operations = mock(ElasticsearchOperations.class);
        var index = mock(IndexOperations.class);
        when(operations.indexOps(any(IndexCoordinates.class))).thenReturn(index);
        when(index.exists()).thenReturn(true);
        var reader = mock(ElasticsearchDeadlineReader.class);
        var store = new ElasticsearchProjectionStore(registry(), operations, new ObjectMapper(), reader);
        var actual = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().valueToTree(store.mapping(space()));
        var properties = (com.fasterxml.jackson.databind.node.ObjectNode) actual.path("properties");
        var metadata = (com.fasterxml.jackson.databind.node.ObjectNode) properties.path("metadata");
        metadata.remove("type");
        switch (mismatch) {
            case "nullType" -> metadata.putNull("type");
            case "nestedType" -> metadata.put("type", "nested");
            case "keywordType" -> metadata.put("type", "keyword");
            case "missingProperties" -> metadata.remove("properties");
            case "emptyProperties" -> metadata.putObject("properties");
            case "nonMapProperties" -> metadata.put("properties", "invalid");
            case "missingMetadata" -> properties.remove("metadata");
            case "missingDynamic" -> metadata.remove("dynamic");
            case "booleanDynamic" -> metadata.put("dynamic", false);
            case "looseDynamic" -> metadata.put("dynamic", "true");
            case "looseRootDynamic" -> actual.put("dynamic", "true");
            case "missingLeafType" -> ((com.fasterxml.jackson.databind.node.ObjectNode)
                    metadata.path("properties").path("projectionVersion")).remove("type");
            case "wrongLeafType" -> ((com.fasterxml.jackson.databind.node.ObjectNode)
                    metadata.path("properties").path("projectionVersion")).put("type", "double");
            case "wrongContentType" -> ((com.fasterxml.jackson.databind.node.ObjectNode)
                    properties.path("content")).put("type", "keyword");
            default -> throw new AssertionError(mismatch);
        }
        schemaResponses(reader, space(), new ObjectMapper().convertValue(actual, Map.class));
        assertEquals(RagErrorCode.SPACE_NOT_READY,
                assertThrows(RagException.class, () -> store.provision(space())).code());
        verify(index, never()).create(anyMap(), any());
        verify(index, never()).putMapping(any(org.springframework.data.elasticsearch.core.document.Document.class));
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
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = new ObjectMapper().convertValue(mapping, Map.class);
        ((Map<?, ?>) ((Map<?, ?>) normalized.get("properties")).get("metadata")).remove("type");
        schemaResponses(reader, memory, normalized);
        assertDoesNotThrow(() -> store.inspectSchema(memory));
        for (Map<String, String> incompatible : List.of(
                Map.of("type", "date", "format", "strict_date_optional_time_nanos"),
                Map.of("type", "date_nanos", "format", "strict_date_optional_time"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> actual = new ObjectMapper().convertValue(normalized, Map.class);
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
            when(response.getStatusLine()).thenReturn(new org.apache.http.message.BasicStatusLine(org.apache.http.HttpVersion.HTTP_1_1, 200, "OK"));
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
    @Test void actualReaderLogsMissingHeadAs404WithoutSensitiveResponseFields() throws Exception {
        assertSafeSchemaDiagnostics(404, false, false, RagErrorCode.SPACE_NOT_READY,
                List.of("operation=SCHEMA_HEAD httpStatus=404 errorCode=SPACE_NOT_READY"), 1);
    }

    @Test void actualReaderLogsMappingMismatchAs200SpaceNotReady() throws Exception {
        assertSafeSchemaDiagnostics(200, false, false, RagErrorCode.SPACE_NOT_READY,
                List.of("operation=SCHEMA_HEAD httpStatus=200 errorCode=NONE",
                        "operation=SCHEMA_MAPPING httpStatus=200 errorCode=SPACE_NOT_READY"), 2);
    }

    @Test void actualReaderLogsHeadTimeoutWithUnknownStatus() throws Exception {
        assertSafeSchemaDiagnostics(200, true, false, RagErrorCode.TIMEOUT,
                List.of("operation=SCHEMA_HEAD httpStatus=UNKNOWN errorCode=TIMEOUT"), 1);
    }

    @Test void actualReaderLogsMappingTimeoutWithoutReusingHeadStatus() throws Exception {
        assertSafeSchemaDiagnostics(200, false, true, RagErrorCode.TIMEOUT,
                List.of("operation=SCHEMA_HEAD httpStatus=200 errorCode=NONE",
                        "operation=SCHEMA_MAPPING httpStatus=UNKNOWN errorCode=TIMEOUT"), 2);
    }

    private static void assertSafeSchemaDiagnostics(int headStatus, boolean stallHead, boolean stallMapping,
            RagErrorCode expectedCode, List<String> expectedLines, int expectedRequests) throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ElasticsearchProjectionStore.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        var originalLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logger.addAppender(appender);
        var client = mock(org.elasticsearch.client.RestClient.class);
        var requests = new ArrayList<org.elasticsearch.client.Request>();
        when(client.performRequestAsync(any(), any())).thenAnswer(call -> {
            var request = call.<org.elasticsearch.client.Request>getArgument(0);
            requests.add(request);
            boolean head = request.getMethod().equals("HEAD");
            if (!(head ? stallHead : stallMapping)) {
                var response = mock(org.elasticsearch.client.Response.class);
                when(response.getStatusLine()).thenReturn(new org.apache.http.message.BasicStatusLine(
                        org.apache.http.HttpVersion.HTTP_1_1, head ? headStatus : 200, "sensitive-status-reason"));
                if (!head) when(response.getEntity()).thenReturn(new org.apache.http.entity.StringEntity(
                        new ObjectMapper().writeValueAsString(Map.of(space().elasticsearchIndex(), Map.of("mappings",
                                Map.of("sensitive-body", "credentials https://private.invalid/secret")))), java.nio.charset.StandardCharsets.UTF_8));
                // Real ES RestClient reports HEAD404 through onSuccess; the production reader returns that status.
                call.<org.elasticsearch.client.ResponseListener>getArgument(1).onSuccess(response);
            }
            return mock(org.elasticsearch.client.Cancellable.class);
        });
        var reader = new ElasticsearchDeadlineReader(client);
        var operations = mock(ElasticsearchOperations.class);
        var store = new ElasticsearchProjectionStore(registry(), operations, new ObjectMapper(), reader);
        try (var ignored = com.bkanent.rag.security.http.RagHttpDeadline.open(java.time.Duration.ofMillis(250))) {
            assertEquals(expectedCode, assertThrows(RagException.class, () -> store.inspectSchema(space())).code());
            assertEquals(expectedRequests, requests.size());
            assertEquals("HEAD", requests.get(0).getMethod());
            if (expectedRequests == 2) assertEquals("GET", requests.get(1).getMethod());
            var events = appender.list;
            assertEquals(expectedLines.size(), events.size());
            for (int i = 0; i < events.size(); i++) {
                var event = events.get(i);
                String line = event.getFormattedMessage();
                assertTrue(line.matches(java.util.regex.Pattern.quote(expectedLines.get(i)) + " elapsedMillis=[0-9]+"), line);
                assertNull(event.getThrowableProxy());
                assertFalse(line.contains(space().elasticsearchIndex()));
                assertFalse(line.contains("sensitive"));
                assertFalse(line.contains("credentials"));
                assertFalse(line.contains("private.invalid"));
            }
            verifyNoInteractions(operations);
        } finally {
            reader.close();
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
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
