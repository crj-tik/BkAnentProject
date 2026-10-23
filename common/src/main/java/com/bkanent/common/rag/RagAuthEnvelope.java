package com.bkanent.common.rag;

import java.io.Serializable;

/** Dedicated authentication argument, never part of model arguments or an audit/checkpoint. */
public record RagAuthEnvelope(int contractVersion, String keyId, String audience, String callerService,
                              RagOperation operation, String requestId, String requestHash,
                              RagActorMode actorMode, String actorId, long issuedAtEpochSecond,
                              long expiresAtEpochSecond, String credentialDigest, String signature,
                              String algorithm) implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final long MAX_TTL_SECONDS = RagContract.MAX_TTL_SECONDS;
    public static final String HMAC_SHA256 = "HMAC-SHA256";

    /** Source and wire compatibility for the original HMAC-v1 envelope. */
    public RagAuthEnvelope(int contractVersion, String keyId, String audience, String callerService,
                           RagOperation operation, String requestId, String requestHash, RagActorMode actorMode,
                           String actorId, long issuedAtEpochSecond, long expiresAtEpochSecond,
                           String credentialDigest, String signature) {
        this(contractVersion, keyId, audience, callerService, operation, requestId, requestHash, actorMode,
                actorId, issuedAtEpochSecond, expiresAtEpochSecond, credentialDigest, signature, HMAC_SHA256);
    }

    @Override
    public String toString() { return "RagAuthEnvelope[REDACTED]"; }
}
