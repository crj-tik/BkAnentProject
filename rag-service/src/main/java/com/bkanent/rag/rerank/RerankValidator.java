package com.bkanent.rag.rerank;

import com.bkanent.common.rag.*;
import java.util.*;

/** Both protocols must return every input exactly once, including low scoring documents. */
public final class RerankValidator {
    public record RankedIndex(Integer index, Double score) { }

    public List<RagCandidate> apply(List<RagCandidate> candidates, List<RankedIndex> results,
                                    RerankProfile profile) {
        if (results == null || results.size() != candidates.size()) protocolError();
        Set<Integer> seen = new HashSet<>();
        List<RagCandidate> ranked = new ArrayList<>();
        for (RankedIndex result : results) {
            if (result == null || result.index() == null || result.index() < 0
                    || result.index() >= candidates.size() || !seen.add(result.index())
                    || result.score() == null || !Double.isFinite(result.score())) protocolError();
            RagCandidate c = candidates.get(result.index());
            double relevance = profile.normalize(result.score());
            ranked.add(new RagCandidate(c.documentId(), c.sourceType(), c.sourceId(), c.content(),
                    c.projectionVersion(), c.schemaVersion(), c.sources(), c.keywordScore(), c.vectorScore(),
                    c.fusionScore(), result.score(), relevance, RagScoreKind.SEMANTIC_NORMALIZED,
                    profile.scoringVersion(), c.metadata()));
        }
        return ranked.stream().sorted(Comparator.comparing(RagCandidate::relevanceScore).reversed()
                .thenComparing(RagCandidate::documentId)).toList();
    }

    private static void protocolError() { throw new RagException(RagErrorCode.RERANK_PROTOCOL_ERROR); }
}
