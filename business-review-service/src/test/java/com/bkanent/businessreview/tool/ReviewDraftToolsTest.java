package com.bkanent.businessreview.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewDraftToolsTest {
    @Test
    void agentToolOnlyCreatesAnUnknownEmptyDraft() {
        var draft = new ReviewDraftTools().draftReviewStructure("  showing follow-up  ");

        assertThat(draft).containsEntry("artifactType", "store-business-review-draft")
                .containsEntry("focus", "showing follow-up")
                .containsEntry("dataMode", "UNKNOWN")
                .containsEntry("requiresUserReview", true)
                .containsEntry("writesBusinessState", false)
                .containsEntry("confirmedFacts", java.util.List.of())
                .containsEntry("hypotheses", java.util.List.of())
                .containsEntry("evidenceGaps", java.util.List.of())
                .containsEntry("suggestedActions", java.util.List.of());
    }

    @Test
    void boundsModelControlledFocus() {
        var draft = new ReviewDraftTools().draftReviewStructure("x".repeat(500));

        assertThat((String) draft.get("focus")).hasSize(300);
    }
}
