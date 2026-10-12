package com.bkanent.rag.rerank;

import com.bkanent.common.rag.*;

/** The deployed provider/model must explicitly declare its verified score scale. */
public record RerankProfile(String provider, String modelVersion, String scoringVersion, Scale scale) {
    public enum Scale { LOGIT, BOUNDED_0_1 }

    public double normalize(double score) {
        if (provider == null || provider.isBlank() || modelVersion == null || modelVersion.isBlank()
                || scoringVersion == null || scoringVersion.isBlank() || scale == null) {
            throw new RagException(RagErrorCode.SCORING_PROFILE_MISMATCH);
        }
        if (!Double.isFinite(score)) throw new RagException(RagErrorCode.RERANK_PROTOCOL_ERROR);
        if (scale == Scale.BOUNDED_0_1) {
            if (score < 0 || score > 1) throw new RagException(RagErrorCode.SCORING_PROFILE_MISMATCH);
            return score;
        }
        // Stable sigmoid for both very positive and very negative finite logits.
        if (score >= 0) return 1 / (1 + Math.exp(-score));
        double exp = Math.exp(score);
        return exp / (1 + exp);
    }
}
