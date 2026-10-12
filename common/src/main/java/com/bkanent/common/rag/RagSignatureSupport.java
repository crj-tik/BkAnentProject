package com.bkanent.common.rag;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

final class RagSignatureSupport {
    private RagSignatureSupport() { }

    static String sign(byte[] payload, byte[] secret) {
        RagContract.require(secret != null && secret.length >= 32, "secret");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload));
        } catch (GeneralSecurityException e) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
    }

    static void verify(byte[] payload, String signature, byte[] secret) {
        authenticate(signature != null && signature.matches("[A-Za-z0-9_-]{43}"));
        try {
            byte[] supplied = Base64.getUrlDecoder().decode(signature);
            byte[] expected = Base64.getUrlDecoder().decode(sign(payload, secret));
            authenticate(MessageDigest.isEqual(expected, supplied));
            // Reject noncanonical final pad bits as well as alternate encodings.
            authenticate(Base64.getUrlEncoder().withoutPadding().encodeToString(supplied).equals(signature));
        } catch (IllegalArgumentException e) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
    }

    static void authenticate(boolean condition) {
        if (!condition) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
    }

    static void authorize(boolean condition) {
        if (!condition) throw new RagException(RagErrorCode.FORBIDDEN);
    }

    static void equal(Object expected, Object actual) {
        authenticate(expected != null && Objects.equals(expected, actual));
    }

    static void hash(String hash) { authenticate(hash != null && hash.matches("[0-9a-f]{64}")); }

    static void text(String value) {
        authenticate(value != null && !value.isBlank() && value.length() <= 512
                && value.codePoints().noneMatch(Character::isISOControl));
    }

    static void time(long issuedAt, long expiresAt, Instant now) {
        authenticate(now != null && issuedAt >= 0 && expiresAt > issuedAt
                && expiresAt - issuedAt <= RagContract.MAX_TTL_SECONDS);
        if (expiresAt <= now.getEpochSecond()) throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        authenticate(issuedAt <= now.getEpochSecond());
    }
}
