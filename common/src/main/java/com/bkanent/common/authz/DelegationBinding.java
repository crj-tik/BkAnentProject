package com.bkanent.common.authz;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** Exact new execution/control request. Auth persists nonce consumption; common never does. */
public record DelegationBinding(String callerService, String audience, String actorId,
                                List<String> operations, String runId, String taskId, String requestId,
                                String bodyHash, String policyDigest, String nonce,
                                long issuedAtEpochSecond, long expiresAtEpochSecond) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public DelegationBinding {
        callerService = AuthzContract.service(callerService);
        audience = AuthzContract.service(audience);
        actorId = AuthzContract.actor(actorId);
        operations = AuthzContract.permissions(operations);
        AuthzContract.require(!operations.isEmpty());
        runId = AuthzContract.nullableText(runId, 128);
        taskId = AuthzContract.nullableText(taskId, 128);
        requestId = AuthzContract.text(requestId, 128);
        bodyHash = AuthzContract.hash(bodyHash);
        policyDigest = AuthzContract.hash(policyDigest);
        nonce = AuthzContract.nonce(nonce);
        AuthzContract.period(issuedAtEpochSecond, expiresAtEpochSecond, AuthzDeadlines.REQUEST_SECONDS);
    }

    @Override public String toString() { return "DelegationBinding[REDACTED]"; }
}
