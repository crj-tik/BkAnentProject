package com.bkanent.common.memory;

import java.util.Set;

/** Safe, bounded root fields that may cross a memory sharing boundary. */
public final class MemorySharedExportFields {
    public static final int MAX_RENDERED_UTF8_BYTES = 24_576;
    private static final Set<String> ROOT_FIELDS = Set.of("result", "summary", "facts", "fact", "decisions",
            "decision", "evidence", "status", "outcome", "recommendations", "citations", "links");

    private MemorySharedExportFields() { }

    public static boolean allowsRootField(String field) {
        return field != null && ROOT_FIELDS.contains(field);
    }

    public static Set<String> allowedRootFields() {
        return ROOT_FIELDS;
    }
}
