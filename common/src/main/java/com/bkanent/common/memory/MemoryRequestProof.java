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
    public static final int ED25519_V2 = 2;
    public enum Actor { USER, SERVICE }

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
                && signature != null && signature.matches("[A-Za-z0-9_-]{86}");
        MemoryContract.require(ed25519V2, MemoryException.Field.IDENTITY);
    }

    @Override
    public String toString() {
        return "MemoryRequestProof[REDACTED]";
    }
}
