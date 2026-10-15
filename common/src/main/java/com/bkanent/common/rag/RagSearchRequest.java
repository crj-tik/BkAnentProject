package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.List;

/** Business-only request. Identity, access token and authorization scope are separate RPC arguments. */
public record RagSearchRequest(int contractVersion, String requestId, String spaceId, String query,
                               List<RagFilter> filters, RagRecallOptions recall, RagFusionOptions fusion,
                               Boolean semanticRerank, Integer rerankCandidates, Integer candidateLimit,
                               RagFailurePolicy failurePolicy) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagSearchRequest {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.text(spaceId, "spaceId");
        RagContract.require(query != null && !query.isBlank() && query.length() <= 8192, "query");
        filters = RagContract.list(filters);
        RagContract.require(filters.size() <= 100, "filters");
    }

    // fastjson2 JSONB record path: an explicit @JSONCreator factory routes decoding through the
    // canonical constructor instead of the FieldBased reflection reader, which crashes on list
    // fields through Dubbo's security handler on fastjson2 2.0.52..2.0.59 (KI-113 follow-up).
    // Jackson never reads fastjson2 annotations; the wire contract is unchanged.
    @com.alibaba.fastjson2.annotation.JSONCreator
    public static RagSearchRequest of(int contractVersion, String requestId, String spaceId, String query,
                                      java.util.List<RagFilter> filters, RagRecallOptions recall,
                                      RagFusionOptions fusion, Boolean semanticRerank, Integer rerankCandidates,
                                      Integer candidateLimit, RagFailurePolicy failurePolicy) {
        return new RagSearchRequest(contractVersion, requestId, spaceId, query, filters, recall, fusion,
                semanticRerank, rerankCandidates, candidateLimit, failurePolicy);
    }

    public static RagSearchRequest defaults(String requestId, String spaceId, String query) {
        return new RagSearchRequest(1, requestId, spaceId, query, null, null, null, null, null, null, null).normalized();
    }

    public RagSearchRequest normalized() {
        RagRecallOptions r = (recall == null ? new RagRecallOptions(null, null, null, null) : recall).normalized();
        RagFusionOptions f = (fusion == null ? new RagFusionOptions(null, null, null, null) : fusion).normalized();
        boolean rerank = semanticRerank == null || semanticRerank;
        int limit = candidateLimit == null ? 50 : candidateLimit;
        RagContract.require(limit >= 1 && limit <= 200, "candidateLimit");
        RagContract.require(rerank || rerankCandidates == null, "rerankCandidates");
        Integer pool = rerank ? (rerankCandidates == null ? 100 : rerankCandidates) : null;
        if (pool != null) RagContract.require(pool >= limit && pool <= 500, "rerankCandidates");
        RagContract.require(r.capacity() >= (rerank ? pool : limit), "recall");
        return new RagSearchRequest(contractVersion, requestId, spaceId, query, filters, r, f, rerank, pool, limit,
                failurePolicy == null ? RagFailurePolicy.STRICT : failurePolicy);
    }

    public RagSearchMode requestedMode() {
        RagSearchRequest n = normalized();
        if (n.recall.hybrid()) return n.semanticRerank ? RagSearchMode.HYBRID_RERANK : RagSearchMode.HYBRID;
        if (n.recall.singleRecall() == RagRecallMode.KEYWORD) {
            return n.semanticRerank ? RagSearchMode.KEYWORD_RERANK : RagSearchMode.KEYWORD;
        }
        return n.semanticRerank ? RagSearchMode.VECTOR_RERANK : RagSearchMode.VECTOR;
    }

    @Override
    public String toString() { return "RagSearchRequest[contractVersion=" + contractVersion + ", payload=REDACTED]"; }
}
