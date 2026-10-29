package com.bkanent.knowledge.domain;

import com.bkanent.common.knowledge.KnowledgeBody;

import java.time.OffsetDateTime;

public record KnowledgeRevisionRecord(long revisionId, String knowledgeId, int revisionNo,
                                      String contentHash, String summary, KnowledgeBody body,
                                      String dataMode, String evidenceLevel, String inputHash,
                                      String rulesVersion, String createdByActorId,
                                      OffsetDateTime createdAt) {
    @Override public String toString() {
        return "KnowledgeRevisionRecord[id=" + revisionId + ", knowledgeId=" + knowledgeId
                + ", revisionNo=" + revisionNo + ", body=REDACTED]";
    }
}
