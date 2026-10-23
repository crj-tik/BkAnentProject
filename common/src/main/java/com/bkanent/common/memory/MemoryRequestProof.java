package com.bkanent.common.memory;

import java.io.Serializable;
import com.bkanent.common.security.ServiceIdentityCrypto;

/**
 * Dedicated short-lived request signature metadata. It contains no user credential and
 * is passed separately from memory payload/model context.
 */
public record MemoryRequestProof(String keyId,
                                 long issuedAtEpochSecond,
                                 long expiresAtEpochSecond,
                                 String nonce,
                                 String signature, Actor actor,
                                 int signatureVersion, String algorithm) implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int HMAC_V1 = 1;
    public static final int ED25519_V2 = 2;
    public static final String HMAC_SHA256 = "HMAC-SHA256";
    public enum Actor { USER, SERVICE }

    public MemoryRequestProof(String keyId, long issuedAtEpochSecond, long expiresAtEpochSecond,
                              String nonce, String signature) {
        this(keyId, issuedAtEpochSecond, expiresAtEpochSecond, nonce, signature, Actor.USER, HMAC_V1, HMAC_SHA256);
    }

    /** Source compatibility for existing v1 callers and tests. */
    public MemoryRequestProof(String keyId, long issuedAtEpochSecond, long expiresAtEpochSecond,
                              String nonce, String signature, Actor actor) {
        this(keyId, issuedAtEpochSecond, expiresAtEpochSecond, nonce, signature, actor, HMAC_V1, HMAC_SHA256);
    }

    public MemoryRequestProof {
        MemoryContract.required(actor, MemoryException.Field.IDENTITY);
        keyId = MemoryContract.id(keyId, MemoryException.Field.IDENTITY);
        MemoryContract.require(keyId.length() <= 64, MemoryException.Field.IDENTITY);
        MemoryContract.require(issuedAtEpochSecond >= 0
                        && expiresAtEpochSecond > issuedAtEpochSecond
                        && expiresAtEpochSecond - issuedAtEpochSecond <= MemoryRequestSigner.MAX_TTL_SECONDS,
                MemoryException.Field.IDENTITY);
        MemoryContract.require(nonce != null && nonce.matches("[A-Za-z0-9_-]{22,64}"),
                MemoryException.Field.IDENTITY);
        boolean legacyShape = signatureVersion == 0 && algorithm == null && signature != null
                && signature.matches("[A-Za-z0-9_-]{43}");
        boolean hmacV1 = signatureVersion == HMAC_V1
                && (algorithm == null || HMAC_SHA256.equals(algorithm))
                && signature != null && signature.matches("[A-Za-z0-9_-]{43}");
        boolean ed25519V2 = signatureVersion == ED25519_V2 && ServiceIdentityCrypto.ED25519.equals(algorithm)
                && signature != null && signature.matches("[A-Za-z0-9_-]{86}");
        MemoryContract.require(legacyShape || hmacV1 || ed25519V2, MemoryException.Field.IDENTITY);
    }

    @Override
    public String toString() {
        return "MemoryRequestProof[REDACTED]";
    }
}
