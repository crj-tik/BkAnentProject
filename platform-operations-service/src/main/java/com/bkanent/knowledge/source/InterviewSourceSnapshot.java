package com.bkanent.knowledge.source;

/** Internal-only sanitized source body; never used as an Agent tool response or Memory event. */
public record InterviewSourceSnapshot(SourceBinding binding, String title, String content,
                                      String archiveGrade, String sourceRelation) {
    @Override
    public String toString() {
        return "InterviewSourceSnapshot[assetId=" + binding.assetId() + ", content=REDACTED]";
    }
}
