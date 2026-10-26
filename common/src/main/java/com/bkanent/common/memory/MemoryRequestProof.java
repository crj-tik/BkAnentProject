package com.bkanent.common.memory;

import java.io.Serializable;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthzFreshness;

/**
 * Dedicated short-lived request signature metadata. It contains no user credential and
 * is passed separately from memory payload/model context.
 */
public record MemoryRequestProof(String keyId,
                                 long issuedAtEpochSecond,
                                 long expiresAtEpochSecond,
                                 String nonce,
                                 String signature, Actor actor,
                                 int signatureVersion, String algorithm,
                                 AuthorizationContext authorizationContext,
                                 AuthzFreshness authorizationFreshness) implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int ED25519_V2 = 2;
    public static final int ED25519_AUTHZ_V3 = 3;
    public enum Actor { USER, SERVICE }

    /** Source-compatible constructor for the existing caller-signed v2 proof. */
    public MemoryRequestProof(String keyId, long issuedAtEpochSecond, long expiresAtEpochSecond, String nonce,
                              String signature, Actor actor, int signatureVersion, String algorithm) {
        this(keyId, issuedAtEpochSecond, expiresAtEpochSecond, nonce, signature, actor, signatureVersion,
                algorithm, null, null);
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
        boolean ed25519V2 = signatureVersion == ED25519_V2 && ServiceIdentityCrypto.ED25519.equals(algorithm)
                && authorizationContext == null && authorizationFreshness == null
                && signature != null && signature.matches("[A-Za-z0-9_-]{86}");
        boolean ed25519AuthzV3 = signatureVersion == ED25519_AUTHZ_V3
                && ServiceIdentityCrypto.ED25519.equals(algorithm) && actor == Actor.USER
                && authorizationContext != null && authorizationFreshness != null
                && signature != null && signature.matches("[A-Za-z0-9_-]{86}");
        MemoryContract.require(ed25519V2 || ed25519AuthzV3, MemoryException.Field.IDENTITY);
    }

    @Override
    public String toString() {
        return "MemoryRequestProof[REDACTED]";
    }
}
