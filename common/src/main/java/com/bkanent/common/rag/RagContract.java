package com.bkanent.common.rag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared v1 structural checks; authorization and space field registration remain server responsibilities. */
public final class RagContract {
    public static final int VERSION = 1;
    public static final String RAG_AUDIENCE = "rag-service";
    public static final String AUTH_AUDIENCE = "auth-service";
    public static final int MAX_RESOURCE_IDS = 5000;
    public static final long MAX_TTL_SECONDS = 60;

    private RagContract() { }

    static void require(boolean condition, String field) {
        if (!condition) throw new RagException(RagErrorCode.INVALID_ARGUMENT, field);
    }

    static String text(String value, String field) {
        require(value != null && !value.isBlank() && value.length() <= 512
                && value.codePoints().noneMatch(Character::isISOControl), field);
        return value;
    }

    static void version(int version) { require(version == VERSION, "contractVersion"); }

    static <T> List<T> list(List<T> values) {
        if (values == null) return List.of();
        require(values.stream().noneMatch(java.util.Objects::isNull), "values");
        return List.copyOf(values);
    }

    static Map<String, RagValue> attributes(Map<String, RagValue> values) {
        if (values == null) return Map.of();
        Map<String, RagValue> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            text(key, "attributes");
            require(value != null, "attributes");
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    static void selector(String eventId, String documentId) {
        require((eventId == null) != (documentId == null), "selector");
        if (eventId != null) text(eventId, "eventId");
        if (documentId != null) text(documentId, "documentId");
    }
}
