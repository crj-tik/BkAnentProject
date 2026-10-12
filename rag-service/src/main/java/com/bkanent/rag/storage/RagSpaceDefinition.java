package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import com.bkanent.rag.rerank.RerankProfile;
import java.util.*;

/** Immutable managed snapshot. Physical targets are never accepted from a search request. */
public record RagSpaceDefinition(String spaceId, String ownerDomain, String policyVersion,
        String schemaVersion, String elasticsearchIndex, VectorSchema vectorSchema,
        Map<String, FilterField> fields, Set<String> returnFields, Set<String> searchRoles,
        Set<String> adminRoles, Map<String, Set<RagOperation>> allowedClients,
        Limits limits, RerankProfile rerankProfile) {
    public RagSpaceDefinition {
        fields = Map.copyOf(fields);
        returnFields = Set.copyOf(returnFields);
        searchRoles = Set.copyOf(searchRoles);
        adminRoles = Set.copyOf(adminRoles);
        Map<String, Set<RagOperation>> clients = new HashMap<>();
        allowedClients.forEach((key, value) -> clients.put(key, Set.copyOf(value)));
        allowedClients = Map.copyOf(clients);
    }

    public record VectorSchema(String database, String collection, String modelVersion, int dimension,
            String idField, String contentField, String metadataField, String vectorField,
            String sourceTypeField, String sourceIdField, String metricType, String indexType,
            String indexParameters) { }

    public record FilterField(RagValueType type, Set<RagFilterOperator> operators, boolean required) {
        public FilterField { operators = Set.copyOf(operators); }
    }

    public record Limits(int maxRecallCandidates, int maxRerankCandidates, int maxCandidateLimit,
            int maxDocumentUtf8Bytes, long maxRecallUtf8Bytes, long maxRerankUtf8Bytes,
            long overallTimeoutMillis, long recallTimeoutMillis, long rerankTimeoutMillis) { }
}
