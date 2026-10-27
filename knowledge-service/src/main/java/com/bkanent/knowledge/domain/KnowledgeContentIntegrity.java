package com.bkanent.knowledge.domain;

import com.bkanent.common.knowledge.KnowledgeBody;
import com.bkanent.knowledge.source.TranscriptIntegrity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public final class KnowledgeContentIntegrity {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private KnowledgeContentIntegrity() { }

    public static String contentHash(String summary, KnowledgeBody body) {
        try { return TranscriptIntegrity.sha256(MAPPER.writeValueAsString(Map.of("summary", summary, "body", body))); }
        catch (Exception failure) { throw new IllegalStateException("Knowledge content hash failed", failure); }
    }
}
