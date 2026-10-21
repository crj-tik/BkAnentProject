package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import com.bkanent.rag.rerank.RerankProfile;
import java.util.*;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Immutable managed snapshot. Physical targets are never accepted from a search request. */
public record RagSpaceDefinition(String spaceId, String ownerDomain, String policyVersion,
        String schemaVersion, String elasticsearchIndex, VectorSchema vectorSchema,
        Map<String, FilterField> fields, Set<String> returnFields, Set<String> searchRoles,
        Set<String> adminRoles, Map<String, Set<RagOperation>> allowedClients,
        Limits limits, RerankProfile rerankProfile, String memoryRecordType,
        String memoryDomainId, String memoryAgentId, boolean candidateAuthorizationRequired) {
    /** Source-compatible legacy-space constructor. */
    public RagSpaceDefinition(String spaceId, String ownerDomain, String policyVersion,
            String schemaVersion, String elasticsearchIndex, VectorSchema vectorSchema,
            Map<String, FilterField> fields, Set<String> returnFields, Set<String> searchRoles,
            Set<String> adminRoles, Map<String, Set<RagOperation>> allowedClients,
            Limits limits, RerankProfile rerankProfile) {
        this(spaceId, ownerDomain, policyVersion, schemaVersion, elasticsearchIndex, vectorSchema,
                fields, returnFields, searchRoles, adminRoles, allowedClients, limits, rerankProfile,
                null, null, null, false);
    }

    @ConstructorBinding
    public RagSpaceDefinition {
        fields = Map.copyOf(fields);
        returnFields = Set.copyOf(returnFields);
        searchRoles = Set.copyOf(searchRoles);
        adminRoles = Set.copyOf(adminRoles);
        Map<String, Set<RagOperation>> clients = new HashMap<>();
        allowedClients.forEach((key, value) -> clients.put(key, Set.copyOf(value)));
        allowedClients = Map.copyOf(clients);
        if (!candidateAuthorizationRequired) {
            if (memoryRecordType != null || memoryDomainId != null || memoryAgentId != null) invalidMemorySpace();
        } else {
            try {
                var type = com.bkanent.common.rag.MemoryRagRecordType.fromId(memoryRecordType);
                if (!"memory-service".equals(ownerDomain)
                        || !com.bkanent.common.rag.MemoryRagSpaceIds.spaceId(type, memoryDomainId, memoryAgentId).equals(spaceId)) {
                    invalidMemorySpace();
                }
                required(fields, "ownerId", com.bkanent.common.rag.RagValueType.STRING);
                required(fields, "active", com.bkanent.common.rag.RagValueType.BOOLEAN);
                if (type == com.bkanent.common.rag.MemoryRagRecordType.COLLABORATION) {
                    required(fields, "recipientDomainId", com.bkanent.common.rag.RagValueType.STRING);
                    required(fields, "recipientAgentId", com.bkanent.common.rag.RagValueType.STRING);
                    required(fields, "grantId", com.bkanent.common.rag.RagValueType.STRING);
                } else {
                    required(fields, "domainId", com.bkanent.common.rag.RagValueType.STRING);
                    required(fields, "agentId", com.bkanent.common.rag.RagValueType.STRING);
                }
            } catch (RuntimeException invalid) {
                throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.INVALID_ARGUMENT, "memorySpace");
            }
        }
    }

    private static void required(Map<String, FilterField> fields, String name, com.bkanent.common.rag.RagValueType type) {
        FilterField field = fields.get(name);
        if (field == null || field.type() != type || !field.required()) invalidMemorySpace();
    }

    private static void invalidMemorySpace() {
        throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.INVALID_ARGUMENT, "memorySpace");
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
