package com.bkanent.common.authz;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** Supervisor-verified execution identity, covered in full by the independent caller proof. */
public record ExecutionBinding(String callerService, String actorId, String runId, String sessionId,
                               List<String> operations, String ingressRequestHash, String executionHash,
                               String policyDigest, String requestId, String nonce,
                               long issuedAtEpochSecond, long expiresAtEpochSecond,
                               String algorithm) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public ExecutionBinding {
        callerService = AuthzContract.service(callerService);
        actorId = AuthzContract.actor(actorId);
        runId = AuthzContract.text(runId, 128);
        sessionId = AuthzContract.text(sessionId, 128);
        operations = AuthzContract.permissions(operations);
        ingressRequestHash = AuthzContract.hash(ingressRequestHash);
        executionHash = AuthzContract.hash(executionHash);
        policyDigest = AuthzContract.hash(policyDigest);
        requestId = AuthzContract.text(requestId, 128);
        nonce = AuthzContract.nonce(nonce);
        AuthzContract.period(issuedAtEpochSecond, expiresAtEpochSecond, AuthzDeadlines.REQUEST_SECONDS);
        AuthzContract.require(AuthzContract.ALGORITHM.equals(algorithm));
    }

    @Override public String toString() { return "ExecutionBinding[REDACTED]"; }
}
