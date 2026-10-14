package com.bkanent.common.rag;

import java.io.Serializable;

/** Non-secret exact transport request facts; credentials are a separate RPC argument. */
public record A2aBeaconBinding(int contractVersion, String requestId, String callerService, String audience,
                               String actorId, String method, String bodyHash, String taskId,
                               long credentialLeaseExpiresAtEpochSecond) implements Serializable {
    public A2aBeaconBinding(int contractVersion, String requestId, String callerService, String audience,
            String actorId, String method, String bodyHash, String taskId) {
        this(contractVersion, requestId, callerService, audience, actorId, method, bodyHash, taskId,
                java.time.Instant.now().getEpochSecond() + 300);
    }
    public A2aBeaconBinding {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.text(callerService, "callerService");
        RagContract.text(audience, "audience");
        RagContract.text(actorId, "actorId");
        RagContract.text(method, "method");
        RagContract.text(taskId, "taskId");
        RagContract.require(bodyHash != null && bodyHash.matches("[0-9a-f]{64}"), "bodyHash");
        RagContract.require(credentialLeaseExpiresAtEpochSecond > 0, "credentialLeaseExpiresAtEpochSecond");
    }
}
