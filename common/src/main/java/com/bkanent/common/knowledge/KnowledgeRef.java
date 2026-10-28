package com.bkanent.common.knowledge;

import java.util.Objects;
import java.util.regex.Pattern;

public record KnowledgeRef(String knowledgeId, String revision, String contentHash) {
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");

    public KnowledgeRef {
        knowledgeId = required(knowledgeId, 64);
        revision = required(revision, 64);
        if (contentHash != null && !HASH.matcher(contentHash).matches()) throw new IllegalArgumentException("INVALID_ARGUMENT");
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("INVALID_ARGUMENT");
        return value;
    }
}
