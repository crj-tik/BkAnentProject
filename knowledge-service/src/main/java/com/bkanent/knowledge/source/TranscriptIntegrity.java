package com.bkanent.knowledge.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class TranscriptIntegrity {
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern PRICE = Pattern.compile("\\d+(?:\\.\\d+)?\\s*(?:万|万元|元|块钱|块)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("(?i)(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}(?![a-z0-9.-])");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{12,}");
    private static final Pattern JWT = Pattern.compile("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");
    private static final Pattern OPAQUE_SECRET = Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{40,128}(?![A-Za-z0-9_-])");
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:access[_-]?token|refresh[_-]?token|session[_-]?ticket|participant[_-]?ticket|ticket|authorization|credential)\\s*[:=]\\s*[^\\s,;]+");

    private TranscriptIntegrity() { }

    public static boolean validHash(String value) { return value != null && HASH.matcher(value).matches(); }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    /** Mirrors Interview Sanitizer.VERSION=1 credential gates without depending on its implementation. */
    public static boolean containsCredentialShape(String value) {
        return value != null && (BEARER.matcher(value).find() || JWT.matcher(value).find()
                || OPAQUE_SECRET.matcher(value).find() || CREDENTIAL_ASSIGNMENT.matcher(value).find());
    }

    public static boolean containsUnredactedPersonalShape(String value) {
        return value != null && (PHONE.matcher(value).find() || ID_CARD.matcher(value).find()
                || EMAIL.matcher(value).find() || PRICE.matcher(value).find());
    }

    public static VerifiedQuote locateQuote(String body, String quote) {
        if (body == null || quote == null || quote.isBlank() || quote.length() > 600) return null;
        int start = body.indexOf(quote);
        if (start < 0 || body.indexOf(quote, start + 1) >= 0) return null;
        int end = start + quote.length();
        return new VerifiedQuote(sha256(quote), start, end);
    }

    public static boolean verifiesQuote(String body, String hash, int start, int end) {
        if (body == null || !validHash(hash) || start < 0 || end <= start || end > body.length()) return false;
        return MessageDigest.isEqual(sha256(body.substring(start, end)).getBytes(StandardCharsets.US_ASCII),
                hash.getBytes(StandardCharsets.US_ASCII));
    }

    public record VerifiedQuote(String quoteHash, int start, int end) { }
}
