package com.bkanent.common.a2a;

import com.bkanent.common.rag.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Dedicated ephemeral execution context, explicitly restored at each supported async boundary. */
public final class A2aCredentialContext {
    private static final ThreadLocal<Lease> CURRENT = new ThreadLocal<>();
    private static final Map<String, Lease> TASKS = new ConcurrentHashMap<>();
    private A2aCredentialContext() { }
    public static final class Lease {
        final A2aBeaconBinding binding;
        final RagCredentialContext credentials;
        final long expiresAt;
        final Runnable validator;
        private volatile boolean dispatched;
        private boolean forwardFinished;
        private boolean commandFinished;
        public Lease(A2aBeaconBinding binding, RagCredentialContext credentials, long expiresAt, Runnable validator) {
            this.binding = binding; this.credentials = credentials; this.expiresAt = expiresAt; this.validator = validator;
        }
        public void verify() {
            if (expiresAt <= java.time.Instant.now().getEpochSecond()) throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
            validator.run();
        }
        @Override public String toString() { return "A2aLease[REDACTED]"; }
    }
    public static final class Scope implements AutoCloseable {
        private final Lease previous;
        private Scope(Lease lease) { previous = CURRENT.get(); if (lease == null) CURRENT.remove(); else CURRENT.set(lease); }
        @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public static Scope open(Lease lease) { return new Scope(lease); }
    public static Lease snapshot() { return CURRENT.get(); }
    public static Lease required() {
        Lease lease = CURRENT.get();
        if (lease == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        lease.verify(); return lease;
    }
    public static RagCredentialContext currentCredentials() { return required().credentials; }
    public static String currentActorId() { return required().binding.actorId(); }
    /** Reserve before the official handler can read or mutate its task store/queue. */
    public static synchronized void reserveTask(String taskId) {
        Lease lease = required();
        if (!taskId.equals(lease.binding.taskId())) throw new RagException(RagErrorCode.FORBIDDEN);
        if (TASKS.containsKey(taskId)) throw new RagException(RagErrorCode.FORBIDDEN);
        if (TASKS.size() >= 10000) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        TASKS.put(taskId, lease);
    }
    public static synchronized void registerTask(String taskId) {
        Lease lease = required();
        if (!taskId.equals(lease.binding.taskId())) throw new RagException(RagErrorCode.FORBIDDEN);
        Lease existing = TASKS.get(taskId);
        // Never replace even an expired reservation: its original command may still run.
        if (existing != null && existing != lease) throw new RagException(RagErrorCode.FORBIDDEN);
        if (existing == null && TASKS.size() >= 10000) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        TASKS.put(taskId, lease);
    }
    static synchronized boolean markDispatched(Lease lease) {
        if (lease == null || TASKS.get(lease.binding.taskId()) != lease) return false;
        lease.dispatched = true;
        return true;
    }
    static synchronized void releaseUndispatchedTask(String taskId, Lease lease) {
        lease.forwardFinished = true;
        if (!lease.dispatched || lease.commandFinished) TASKS.remove(taskId, lease);
    }
    static synchronized void releaseDispatchedTask(Lease lease) {
        lease.commandFinished = true;
        // A fast producer can finish before DefaultRequestHandler publishes its future
        // or completes the original forward. Both sides must finish before reuse.
        if (lease.forwardFinished) TASKS.remove(lease.binding.taskId(), lease);
    }
    public static Scope openTask(String taskId) {
        Lease lease = TASKS.get(taskId);
        if (lease == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        lease.verify(); return open(lease);
    }
    public static synchronized void discardTask(String taskId) {
        Lease lease = CURRENT.get();
        // SDK command completion closes queues after AgentExecutor returns. Keep the
        // reservation until then, so a new send cannot race that old queue cleanup.
        if (lease != null && !lease.dispatched) TASKS.remove(taskId, lease);
    }
}
