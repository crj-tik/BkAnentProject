package com.bkanent.agent.authorization;

import com.bkanent.common.authz.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.function.Supplier;

/** Explicit thread capture, never inheritable, serializable, or stored in RunnableConfig/checkpoints. */
public final class AgentAuthorizationScope {
    public static final String VERIFIED_ATTRIBUTE = AgentAuthorizationScope.class.getName() + ".verified";
    public static final String RESPONSE_ATTRIBUTE = AgentAuthorizationScope.class.getName() + ".response";
    public static final String BINDING_ATTRIBUTE = AgentAuthorizationScope.class.getName() + ".binding";
    private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();

    private AgentAuthorizationScope() { }

    public static final class Entry {
        private final AuthzResponse response;
        private final RequestBinding binding;
        private volatile AuthorizationSession session;
        private String executionTupleHash;
        private ExecutionBinding executionBinding;
        private String defaultRequestId;
        private java.util.function.Consumer<AuthorizationSession> registrationRelease = ignored -> { };
        private boolean leaseCaptured;
        private boolean registrationReleased;
        synchronized void registrationRelease(java.util.function.Consumer<AuthorizationSession> release) {
            registrationRelease = release;
            registrationReleased = false;
        }
        public synchronized void markLeaseCaptured() { leaseCaptured = true; }
        public synchronized boolean hasCapturedLease() { return leaseCaptured; }
        public synchronized void releaseRegistration() {
            if (registrationReleased) return;
            registrationReleased = true;
            registrationRelease.accept(session);
        }
        public synchronized String normalizeRequestId(String requested) {
            if (requested != null && !requested.isBlank()) return requested.trim();
            if (defaultRequestId == null) defaultRequestId = java.util.UUID.randomUUID().toString();
            return defaultRequestId;
        }
        synchronized String executionTupleHash() { return executionTupleHash; }
        public synchronized ExecutionBinding executionBinding() { return executionBinding; }
        synchronized void installBoundSession(AuthorizationSession bound, ExecutionBinding binding, String tupleHash) {
            if (executionTupleHash != null) throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            session = bound;
            executionBinding = binding;
            executionTupleHash = tupleHash;
        }
        Entry(AuthzResponse response, RequestBinding binding, AuthorizationSession session) {
            this.response = response;
            this.binding = binding;
            this.session = session;
        }
        public AuthzResponse response() { return response; }
        public RequestBinding binding() { return binding; }
        public AuthorizationSession session() { return session; }
        public String requireActor(String expected) {
            switch (session.state()) {
                case DIRTY -> throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
                case UNVERIFIED -> throw new AuthzException(AuthzErrorCode.AUTHORIZATION_UNAVAILABLE);
                case EXPIRED -> throw new AuthzException(AuthzErrorCode.EXPIRED);
                case VALID -> { }
            }
            String actor = session.context().actorId();
            if (expected != null && !actor.equals(expected)) throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
            return actor;
        }
        /** A null signed run is an ingress prototype, not permission to create a business run. */
        public void requireRunAssociation() {
            requireActor(null);
            if (session.context().runId() == null || session.context().sessionId() == null) {
                throw new AuthzException(AuthzErrorCode.CONTEXT_CONFLICT);
            }
        }
        @Override public String toString() { return "VerifiedAgentAuthorization[REDACTED]"; }
    }

    public static Entry fromRequest(HttpServletRequest request) {
        Object value = request.getAttribute(VERIFIED_ATTRIBUTE);
        if (!(value instanceof Entry entry) || entry != CURRENT.get()
                || request.getAttribute(RESPONSE_ATTRIBUTE) != entry.response
                || request.getAttribute(BINDING_ATTRIBUTE) != entry.binding) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_REQUIRED);
        }
        entry.requireActor(null);
        return entry;
    }
    public static Entry current() { return CURRENT.get(); }
    public static AutoCloseable open(Entry entry) {
        Entry previous = CURRENT.get();
        CURRENT.remove();
        if (entry != null) CURRENT.set(entry);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static Supplier<AutoCloseable> capture() {
        Entry captured = CURRENT.get();
        return () -> open(captured);
    }
}
