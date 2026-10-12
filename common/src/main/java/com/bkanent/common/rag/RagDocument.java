package com.bkanent.common.rag;

import java.io.Serializable;
import java.util.Map;

public record RagDocument(String sourceType, String sourceId, String content, Map<String, RagValue> attributes) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagDocument {
        attributes = RagContract.attributes(attributes);
        RagContract.text(sourceType, "sourceType");
        RagContract.text(sourceId, "sourceId");
        RagContract.require(content != null && !content.isBlank(), "content");
    }

    @Override
    public String toString() { return "RagDocument[REDACTED]"; }
}
