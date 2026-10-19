package com.bkanent.common.memory;

import java.io.Serializable;

/**
 * Dedicated short-lived request signature metadata. It contains no user credential and
 * is passed separately from memory payload/model context.
 */
public record MemoryRequestProof(String keyId,
                                 long issuedAtEpochSecond,
                                 long expiresAtEpochSecond,
                                 String nonce,
                                 String signature) implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryRequestProof {
        keyId = MemoryContract.id(keyId, MemoryException.Field.IDENTITY);
        MemoryContract.require(keyId.length() <= 64, MemoryException.Field.IDENTITY);
        MemoryContract.require(issuedAtEpochSecond >= 0
                        && expiresAtEpochSecond > issuedAtEpochSecond
                        && expiresAtEpochSecond - issuedAtEpochSecond <= MemoryRequestSigner.MAX_TTL_SECONDS,
                MemoryException.Field.IDENTITY);
        MemoryContract.require(nonce != null && nonce.matches("[A-Za-z0-9_-]{22,64}"),
                MemoryException.Field.IDENTITY);
        MemoryContract.require(signature != null && signature.matches("[A-Za-z0-9_-]{43}"),
                MemoryException.Field.IDENTITY);
    }

    @Override
    public String toString() {
        return "MemoryRequestProof[REDACTED]";
    }
}
