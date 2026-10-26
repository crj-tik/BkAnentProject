package com.bkanent.common.a2a;

import com.bkanent.common.rag.*;
import com.bkanent.common.authz.*;
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
        final AuthorizationContext authorizationContext;
        final AuthzFreshness freshness;
        private final Runnable reservationRelease;
        private final java.util.concurrent.atomic.AtomicBoolean reservationReleased = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile boolean dispatched;
        private boolean forwardFinished;
        private boolean commandFinished;
        public Lease(A2aBeaconBinding binding, RagCredentialContext credentials, long expiresAt, Runnable validator) {
            this(binding, credentials, expiresAt, validator, null, null, null);
        }
        private Lease(A2aBeaconBinding binding, RagCredentialContext credentials, long expiresAt, Runnable validator,
                      AuthorizationContext authorizationContext, AuthzFreshness freshness, Runnable reservationRelease) {
            this.binding = binding; this.credentials = credentials; this.expiresAt = expiresAt; this.validator = validator;
            this.authorizationContext = authorizationContext; this.freshness = freshness;
            this.reservationRelease = reservationRelease;
        }
        static Lease versioned(DelegatedAuthorizationRequest request, String method, Runnable reservationRelease) {
            AuthorizationContext context = request.context();
            AuthzFreshness freshness = request.freshness();
            DelegationBinding binding = request.binding();
            long hardExpiry = Math.min(context.expiresAtEpochSecond(), context.runHardDeadlineEpochSecond());
            long expiresAt = Math.min(hardExpiry, freshness.notAfterEpochSecond());
            A2aBeaconBinding compatibilityBinding = new A2aBeaconBinding(1, binding.requestId(),
                    binding.callerService(), binding.audience(), context.actorId(), method, binding.bodyHash(),
                    binding.taskId(), hardExpiry);
            return new Lease(compatibilityBinding, null, expiresAt, null, context, freshness, reservationRelease);
        }
        public void verify() {
            if (expiresAt <= java.time.Instant.now().getEpochSecond()) throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
            if (validator != null) validator.run();
        }
        public boolean versioned() { return authorizationContext != null; }
        public AuthorizationContext authorizationContext() { return authorizationContext; }
        public AuthzFreshness freshness() { return freshness; }
        private void releaseReservation() {
            if (reservationRelease == null || !reservationReleased.compareAndSet(false, true)) return;
            try { reservationRelease.run(); }
            catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(A2aCredentialContext.class)
                        .warn("A2A durable task reservation cleanup failed");
            }
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
    public static RagCredentialContext currentCredentials() {
        RagCredentialContext credentials = required().credentials;
        if (credentials == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        return credentials;
    }
    public static String currentActorId() {
        Lease lease = required();
        return lease.versioned() ? lease.authorizationContext.actorId() : lease.binding.actorId();
    }
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
    static void releaseUndispatchedTask(String taskId, Lease lease) {
        boolean release;
        synchronized (A2aCredentialContext.class) {
            lease.forwardFinished = true;
            release = !lease.dispatched || lease.commandFinished;
            if (release) TASKS.remove(taskId, lease);
        }
        if (release) lease.releaseReservation();
    }
    static void releaseDispatchedTask(Lease lease) {
        boolean release;
        synchronized (A2aCredentialContext.class) {
            lease.commandFinished = true;
            // A fast producer can finish before DefaultRequestHandler publishes its future
            // or completes the original forward. Both sides must finish before reuse.
            release = lease.forwardFinished;
            if (release) TASKS.remove(lease.binding.taskId(), lease);
        }
        if (release) lease.releaseReservation();
    }
    public static Scope openTask(String taskId) {
        Lease lease = TASKS.get(taskId);
        if (lease == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        lease.verify(); return open(lease);
    }
    public static void discardTask(String taskId) {
        Lease lease = CURRENT.get();
        boolean release = false;
        synchronized (A2aCredentialContext.class) {
            // SDK command completion closes queues after AgentExecutor returns. Keep the
            // reservation until then, so a new send cannot race that old queue cleanup.
            if (lease != null && !lease.dispatched) release = TASKS.remove(taskId, lease);
        }
        if (release) lease.releaseReservation();
    }
}
