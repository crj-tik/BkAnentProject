package com.bkanent.rag.rerank;

import com.bkanent.common.rag.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.bkanent.rag.retrieval.RetrievalTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class RerankValidatorTest {
    private final RerankValidator validator = new RerankValidator();
    private final List<RagCandidate> candidates = List.of(hit("a", 1, RagRecallMode.KEYWORD).candidate(), hit("b", 2, RagRecallMode.VECTOR).candidate());
    private final RerankProfile bounded = space().rerankProfile();
    @Test void requiresCompleteUniqueFiniteInRangeIndices() {
        List<List<RerankValidator.RankedIndex>> invalid = List.of(
                List.of(new RerankValidator.RankedIndex(0, .8)),
                List.of(new RerankValidator.RankedIndex(0, .8), new RerankValidator.RankedIndex(0, .7)),
                List.of(new RerankValidator.RankedIndex(0, .8), new RerankValidator.RankedIndex(2, .7)),
                List.of(new RerankValidator.RankedIndex(0, .8), new RerankValidator.RankedIndex(1, Double.NaN)),
                List.of(new RerankValidator.RankedIndex(null, .8), new RerankValidator.RankedIndex(1, .7)));
        for (var response : invalid) assertEquals(RagErrorCode.RERANK_PROTOCOL_ERROR,
                assertThrows(RagException.class, () -> validator.apply(candidates, response, bounded)).code());
        assertThrows(RagException.class, () -> validator.apply(candidates, null, bounded));
    }
    @Test void explicitScaleNormalizesAndStableSorts() {
        var profile = new RerankProfile("native", "bge-1", "logit-v1", RerankProfile.Scale.LOGIT);
        var result = validator.apply(candidates, List.of(new RerankValidator.RankedIndex(1, 0D), new RerankValidator.RankedIndex(0, 0D)), profile);
        assertEquals("a", result.get(0).documentId());
        assertEquals(.5, result.get(0).relevanceScore());
        assertEquals(0D, result.get(0).rawRerankScore());
        assertEquals(RagScoreKind.SEMANTIC_NORMALIZED, result.get(0).scoreKind());
        assertEquals(1, profile.normalize(1000));
        assertEquals(0, profile.normalize(-1000));
    }
    @Test void rejectsUndeclaredAndOutOfBoundsProfiles() {
        assertEquals(RagErrorCode.SCORING_PROFILE_MISMATCH, assertThrows(RagException.class, () -> bounded.normalize(2)).code());
        assertThrows(RagException.class, () -> new RerankProfile("native", "m", "v", null).normalize(.2));
    }
}
