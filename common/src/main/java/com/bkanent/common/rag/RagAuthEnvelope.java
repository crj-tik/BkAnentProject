package com.bkanent.common.rag;

import java.io.Serializable;

/** Dedicated authentication argument, never part of model arguments or an audit/checkpoint. */
public record RagAuthEnvelope(int contractVersion, String keyId, String audience, String callerService,
                              RagOperation operation, String requestId, String requestHash,
                              RagActorMode actorMode, String actorId, long issuedAtEpochSecond,
                              long expiresAtEpochSecond, String credentialDigest, String signature) implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final long MAX_TTL_SECONDS = RagContract.MAX_TTL_SECONDS;

    @Override
    public String toString() { return "RagAuthEnvelope[REDACTED]"; }
}
