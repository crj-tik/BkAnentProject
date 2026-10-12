package com.bkanent.rag.retrieval;

import com.bkanent.common.rag.*;
import com.bkanent.rag.rerank.RerankProfile;
import com.bkanent.rag.storage.*;
import java.util.*;

public final class RetrievalTestFixtures {
    private RetrievalTestFixtures() { }
    public static RagSpaceDefinition space() {
        return new RagSpaceDefinition("listing.knowledge", "listing-master", "policy-1", "schema-1", "rag_listing_v1",
                new RagSpaceDefinition.VectorSchema("default", "rag_listing_v1", "embedding-1", 3,
                        "doc_id", "content", "metadata", "embedding", "source_type", "source_id", "COSINE", "IVF_FLAT", "{\"nlist\":1024}"),
                Map.of("ownerId", field(RagValueType.STRING), "resourceId", field(RagValueType.STRING),
                        "region", field(RagValueType.STRING), "area", field(RagValueType.NUMBER), "active", field(RagValueType.BOOLEAN)),
                Set.of("region", "area"), Set.of("ADMIN"), Set.of("ADMIN"), Map.of("agent-service", Set.of(RagOperation.SEARCH)),
                new RagSpaceDefinition.Limits(500, 500, 200, 8192, 100000, 100000, 1000, 300, 300),
                new RerankProfile("native", "bge-1", "semantic-1", RerankProfile.Scale.BOUNDED_0_1));
    }
    private static RagSpaceDefinition.FilterField field(RagValueType type) {
        return new RagSpaceDefinition.FilterField(type, Set.of(RagFilterOperator.values()), false);
    }
    public static RecallHit hit(String id, double score, RagRecallMode mode) {
        return new RecallHit(new RagCandidate(id, "listing", id, "text " + id, 1, "schema-1", List.of(mode),
                mode == RagRecallMode.KEYWORD ? score : null, mode == RagRecallMode.VECTOR ? score : null,
                null, null, null, null, null, Map.of("contentHash", RagValue.string("hash"), "ownerId", RagValue.string("a"))), "hash");
    }
    public static RagSpaceRegistry registry() {
        return new RagSpaceRegistry() {
            public RagSpaceDefinition require(String id) { return space(); }
            public RagSpaceDefinition requireReady(String id) { return space(); }
        };
    }
    public static RagSearchRequest request(boolean rerank, RagFailurePolicy policy) {
        return new RagSearchRequest(1, "request-1", "listing.knowledge", "query", List.of(),
                new RagRecallOptions(true, null, 2, 2), new RagFusionOptions(null, null, null, null), rerank,
                rerank ? 4 : null, 2, policy).normalized();
    }
}
