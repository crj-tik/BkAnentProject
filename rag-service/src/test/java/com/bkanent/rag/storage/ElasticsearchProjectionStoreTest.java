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
    @Test void inspectRefusesWrongFieldMappingAndProvisionDoesNotOverwriteExistingMapping() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations index = mock(IndexOperations.class);
        when(operations.indexOps(any(IndexCoordinates.class))).thenReturn(index);
        when(index.exists()).thenReturn(true);
        when(index.getMapping()).thenReturn(Map.of("properties", Map.of("content", Map.of("type", "keyword"))));
        var store = new ElasticsearchProjectionStore(registry(), operations, new ObjectMapper(), mock(ElasticsearchDeadlineReader.class));
        assertEquals(RagErrorCode.SPACE_NOT_READY, assertThrows(RagException.class, () -> store.provision(space())).code());
        verify(index, never()).create(anyMap(), any());
        verify(index, never()).putMapping(any(org.springframework.data.elasticsearch.core.document.Document.class));
    }
}
