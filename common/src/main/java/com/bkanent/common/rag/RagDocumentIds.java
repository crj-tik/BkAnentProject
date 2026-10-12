package com.bkanent.common.rag;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Stable bounded identities; never infer an ambiguous legacy underscore composite identity. */
public final class RagDocumentIds {
    private RagDocumentIds() { }

    public static String listing(long id) {
        RagContract.require(id > 0, "listingId");
        return "listing:" + id;
    }

    public static String preference(String userId, String preferenceKey) {
        return "preference:" + segment(userId, 64) + ":" + segment(preferenceKey, 256);
    }

    public static String constraint(String constraintKey) { return "constraint:" + segment(constraintKey, 256); }

    private static String segment(String value, int maximumBytes) {
        RagContract.text(value, "documentId");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        RagContract.require(bytes.length <= maximumBytes, "documentId");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                RagContract.require(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(++i)), "documentId");
            } else RagContract.require(!Character.isLowSurrogate(c), "documentId");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
