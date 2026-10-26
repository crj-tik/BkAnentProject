package com.bkanent.common.a2a;

/** Durable replay, task-owner and in-flight reservation facts shared by A2A instances. */
public interface A2aTaskAuthorizationStore {
    /** Atomically consumes a signed request nonce until its signed expiry. */
    boolean consumeNonce(String nonce, long expiresAtEpochSecond, long nowEpochSecond);

    /** Creates an owner on send, or checks the exact original owner on every operation. */
    boolean bindOrVerifyTaskOwner(String taskId, String actorId, String callerService,
                                  String a2aContextId, String runId, String sessionId, boolean sending);

    /** Reserves a task while one accepted send is being forwarded/executed. */
    boolean reserveTask(String taskId, String actorId, String nonce,
                        long expiresAtEpochSecond, long nowEpochSecond);

    /** Releases only the reservation created by this request. */
    void releaseTaskReservation(String taskId, String nonce);
}
