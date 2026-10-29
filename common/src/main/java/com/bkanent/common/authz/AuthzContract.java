package com.bkanent.common.authz;

import com.bkanent.common.security.ServiceIdentityCrypto;

import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Wire bounds and canonical forms. Changes to these limits must not widen timing windows. */
public final class AuthzContract {
    public static final int MAX_PERMISSIONS = 1024;
    public static final int MAX_PERMISSION_LENGTH = 128;
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    public static final int MAX_QUERY_PARAMETERS = 128;
    public static final int MAX_BUSINESS_IDENTITIES = 8;
    public static final String ALGORITHM = ServiceIdentityCrypto.ED25519;

    private AuthzContract() { }

    static void require(boolean condition) {
        if (!condition) throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
    }

    static void check(boolean condition, AuthzErrorCode code) {
        if (!condition) throw new AuthzException(code);
    }

    static String actor(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"));
        try {
            require(Long.parseLong(value) > 0);
        } catch (NumberFormatException exception) {
            throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
        }
        return value;
    }

    static String text(String value, int max) {
        require(value != null && !value.isBlank() && value.length() <= max);
        validText(value);
        return value;
    }

    static String nullableText(String value, int max) {
        return value == null ? null : text(value, max);
    }

    static void validText(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            require(!Character.isISOControl(c));
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(++i)));
            } else {
                require(!Character.isLowSurrogate(c));
            }
        }
    }

    static String service(String value) {
        require(value != null && value.matches("[a-z][a-z0-9-]{0,95}"));
        return value;
    }

    static String hash(String value) {
        require(value != null && value.matches("[0-9a-f]{64}"));
        return value;
    }

    static String nonce(String value) {
        require(value != null && value.matches("[A-Za-z0-9_-]{43}"));
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        require(bytes.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value));
        return value;
    }

    static List<String> permissions(List<String> values) {
        require(values != null && values.size() <= MAX_PERMISSIONS);
        TreeSet<String> sorted = new TreeSet<>();
        for (String value : values) {
            require(value != null && value.length() <= MAX_PERMISSION_LENGTH
                    && value.matches("[a-z][a-z0-9_-]*(\\.[a-z][a-z0-9_-]*)+"));
            sorted.add(value);
        }
        return List.copyOf(sorted);
    }

    public static Map<String, Long> businessIdentities(Map<String, Long> values) {
        require(values != null && values.size() <= MAX_BUSINESS_IDENTITIES);
        TreeMap<String, Long> sorted = new TreeMap<>();
        for (var entry : values.entrySet()) {
            require(("BROKER".equals(entry.getKey()) || "STORE".equals(entry.getKey()))
                    && entry.getValue() != null && entry.getValue() > 0);
            sorted.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(sorted);
    }

    static void signature(String algorithm, String keyId, String signature) {
        require(ALGORITHM.equals(algorithm));
        require(keyId != null && keyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"));
        if (signature != null) {
            try {
                ServiceIdentityCrypto.decodeSignature(signature);
            } catch (IllegalArgumentException exception) {
                throw new AuthzException(AuthzErrorCode.INVALID_ARGUMENT);
            }
        }
    }

    static void period(long issuedAt, long expiresAt, long maximumSeconds) {
        require(issuedAt > 0 && expiresAt > issuedAt && expiresAt - issuedAt <= maximumSeconds);
    }
}
