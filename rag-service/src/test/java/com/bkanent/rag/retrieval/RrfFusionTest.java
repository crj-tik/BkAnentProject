package com.bkanent.rag.retrieval;

import com.bkanent.common.rag.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.bkanent.rag.retrieval.RetrievalTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RrfFusionTest {
    private final RrfFusion fusion = new RrfFusion();
    private final RagFusionOptions options = new RagFusionOptions(null, null, null, null).normalized();
    @Test void rankFusionNeverAddsIncompatibleScoresAndDeduplicates() {
        var result = fusion.fuse(List.of(hit("b", 10000, RagRecallMode.KEYWORD), hit("a", 9999, RagRecallMode.KEYWORD),
                        hit("b", 20000, RagRecallMode.KEYWORD)),
                List.of(hit("a", .9, RagRecallMode.VECTOR), hit("b", .8, RagRecallMode.VECTOR)), options, true, true, 50);
        assertEquals(List.of("a", "b"), result.stream().map(RagCandidate::documentId).toList());
        assertEquals(2, result.get(0).sources().size());
        assertEquals(9999, result.get(0).keywordScore());
        assertEquals(.9, result.get(0).vectorScore());
        assertEquals(RagScoreKind.RANK_FUSION, result.get(0).scoreKind());
        assertTrue(result.get(0).relevanceScore() <= 1);
    }
    @Test void singleRouteKeepsRankScaleAndActualInsufficientCount() {
        var result = fusion.fuse(List.of(), List.of(hit("a", .01, RagRecallMode.VECTOR)), options, false, true, 50);
        assertEquals(1, result.size());
        assertEquals(RagScoreKind.RANK_SINGLE, result.get(0).scoreKind());
        assertEquals(1, result.get(0).relevanceScore());
    }
    @Test void cannotMergeDifferentProjectionVersions() {
        RecallHit a = hit("a", 3, RagRecallMode.KEYWORD);
        RecallHit b = new RecallHit(a.candidate(), "new-hash");
        assertEquals(RagErrorCode.INDEX_CONFLICT, assertThrows(RagException.class,
                () -> fusion.fuse(List.of(a), List.of(b), options, true, true, 5)).code());
    }
}
