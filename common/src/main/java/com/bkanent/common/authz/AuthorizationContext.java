package com.bkanent.common.authz;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** Signed scope plus a stable snapshot reference; not a standalone bearer or recovery credential. */
public record AuthorizationContext(String actorId, long permissionRevision, long credentialEpoch,
                                   String permissionHash, List<String> allowedOperations,
                                   String audience, String requestId, String runId, String sessionId,
                                   String policyDigest, String requestHash, String nonce,
                                   long issuedAtEpochSecond, long expiresAtEpochSecond,
                                   long runHardDeadlineEpochSecond, Map<String, Long> businessIdentities,
                                   String algorithm, String keyId,
                                   String signature) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public AuthorizationContext {
        actorId = AuthzContract.actor(actorId);
        AuthzContract.require(permissionRevision > 0 && credentialEpoch >= 0);
        permissionHash = AuthzContract.hash(permissionHash);
        allowedOperations = AuthzContract.permissions(allowedOperations);
        audience = AuthzContract.service(audience);
        requestId = AuthzContract.text(requestId, 128);
        runId = AuthzContract.nullableText(runId, 128);
        sessionId = AuthzContract.nullableText(sessionId, 128);
        policyDigest = AuthzContract.hash(policyDigest);
        requestHash = AuthzContract.hash(requestHash);
        nonce = AuthzContract.nonce(nonce);
        AuthzContract.period(issuedAtEpochSecond, expiresAtEpochSecond, AuthzDeadlines.CONTEXT_SECONDS);
        AuthzContract.require(runHardDeadlineEpochSecond >= expiresAtEpochSecond
                && runHardDeadlineEpochSecond - issuedAtEpochSecond <= AuthzDeadlines.CONTEXT_SECONDS);
        businessIdentities = AuthzContract.businessIdentities(businessIdentities);
        AuthzContract.signature(algorithm, keyId, signature);
    }

    public AuthorizationContext(String actorId, long permissionRevision, long credentialEpoch,
                                String permissionHash, List<String> allowedOperations,
                                String audience, String requestId, String runId, String sessionId,
                                String policyDigest, String requestHash, String nonce,
                                long issuedAtEpochSecond, long expiresAtEpochSecond,
                                long runHardDeadlineEpochSecond, String algorithm, String keyId,
                                String signature) {
        this(actorId, permissionRevision, credentialEpoch, permissionHash, allowedOperations, audience,
                requestId, runId, sessionId, policyDigest, requestHash, nonce, issuedAtEpochSecond,
                expiresAtEpochSecond, runHardDeadlineEpochSecond, Map.of(), algorithm, keyId, signature);
    }

    @Override public String toString() { return "AuthorizationContext[REDACTED]"; }
}
