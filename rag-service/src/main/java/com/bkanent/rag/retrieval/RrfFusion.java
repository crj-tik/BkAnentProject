package com.bkanent.rag.retrieval;

import com.bkanent.common.rag.*;
import java.util.*;

/** Versioned rank fusion. Raw BM25/cosine scores are evidence, never additive weights. */
public final class RrfFusion {
    public static final String VERSION = "rrf-v1";

    public List<RagCandidate> fuse(List<RecallHit> keyword, List<RecallHit> vector,
                                   RagFusionOptions options, boolean keywordEnabled,
                                   boolean vectorEnabled, int limit) {
        Map<String, Accumulator> merged = new HashMap<>();
        if (keywordEnabled) add(merged, keyword, RagRecallMode.KEYWORD, options.keywordWeight(), options.rrfK());
        if (vectorEnabled) add(merged, vector, RagRecallMode.VECTOR, options.vectorWeight(), options.rrfK());
        double upper = ((keywordEnabled ? options.keywordWeight() : 0)
                + (vectorEnabled ? options.vectorWeight() : 0)) / (options.rrfK() + 1.0);
        RagScoreKind kind = keywordEnabled && vectorEnabled ? RagScoreKind.RANK_FUSION : RagScoreKind.RANK_SINGLE;
        return merged.values().stream().map(a -> a.candidate(kind, upper))
                .sorted(Comparator.comparing(RagCandidate::fusionScore).reversed()
                        .thenComparing(RagCandidate::documentId))
                .limit(limit).toList();
    }

    private void add(Map<String, Accumulator> merged, List<RecallHit> hits,
                     RagRecallMode source, double weight, int k) {
        Set<String> seen = new HashSet<>();
        int rank = 0;
        for (RecallHit hit : hits) {
            RagCandidate c = hit.candidate();
            boolean first = seen.add(c.documentId());
            Accumulator a = merged.computeIfAbsent(c.documentId(), id -> new Accumulator(hit));
            // A ledger-filtered stream must never contain two versions of the same identity.
            if (a.hit.candidate().projectionVersion() != c.projectionVersion()
                    || !Objects.equals(a.hit.contentHash(), hit.contentHash())
                    || !Objects.equals(a.hit.candidate().schemaVersion(), c.schemaVersion())
                    || !Objects.equals(a.hit.candidate().content(), c.content())) {
                throw new RagException(RagErrorCode.INDEX_CONFLICT);
            }
            if (!first) continue;
            rank++;
            a.sources.add(source);
            a.score += weight / (k + (double) rank);
            if (source == RagRecallMode.KEYWORD) a.keyword = c.keywordScore();
            else a.vector = c.vectorScore();
        }
    }

    private static final class Accumulator {
        final RecallHit hit;
        final List<RagRecallMode> sources = new ArrayList<>();
        double score;
        Double keyword;
        Double vector;
        Accumulator(RecallHit hit) { this.hit = hit; }
        RagCandidate candidate(RagScoreKind kind, double upper) {
            RagCandidate c = hit.candidate();
            return new RagCandidate(c.documentId(), c.sourceType(), c.sourceId(), c.content(),
                    c.projectionVersion(), c.schemaVersion(), List.copyOf(sources), keyword, vector,
                    score, null, score / upper, kind, VERSION, c.metadata());
        }
    }
}
