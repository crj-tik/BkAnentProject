package com.bkanent.agent.security;

import com.bkanent.agent.config.AgentRagConsumerProperties;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Bounded, process-local credentials. Never attached to model/RunnableConfig or durable requests. */
@Component
public class AgentRagCredentialLeaseStore {
    private static final ThreadLocal<Lease> CURRENT = new ThreadLocal<>();
    private final Map<String, Lease> leases = new HashMap<>();
    private final AgentRagConsumerProperties properties;
    private final ObjectProvider<AuthPermissionRpcService> auth;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRagCredentialLeaseStore(AgentRagConsumerProperties properties, ObjectProvider<AuthPermissionRpcService> auth) {
        this(properties, auth, Clock.systemUTC());
    }
    public AgentRagCredentialLeaseStore(AgentRagConsumerProperties properties, ObjectProvider<AuthPermissionRpcService> auth, Clock clock) {
        this.properties = properties; this.auth = auth; this.clock = clock;
    }
    public AutoCloseable openVerifiedAuthorization(String token,
            com.bkanent.agent.authorization.AgentAuthorizationScope.Entry entry) {
        if (entry == null || entry != com.bkanent.agent.authorization.AgentAuthorizationScope.current())
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
        String actor = entry.requireActor(null);
        Lease previous = CURRENT.get();
        long expires = Math.min(clock.millis() + Math.min(300, Math.max(1, properties.getCredentialLeaseSeconds())) * 1000,
                entry.session().context().expiresAtEpochSecond() * 1000);
        CURRENT.set(new Lease(actor, new RagCredentialContext(token), expires, entry));
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }

    public AutoCloseable openAuthorization(String header) {
        Lease previous = CURRENT.get();
        CURRENT.remove();
        if (header != null) {
            if (!header.regionMatches(true, 0, "Bearer ", 0, 7) || header.substring(7).isBlank()
                    || header.substring(7).trim().length() > 16384) {
                if (previous != null) CURRENT.set(previous);
                throw new AgentRagAuthenticationException("AUTHENTICATION_REQUIRED");
            }
            long ttl = Math.min(300, Math.max(1, properties.getCredentialLeaseSeconds()));
            CURRENT.set(new Lease(null, new RagCredentialContext(header.substring(7).trim()), clock.millis() + ttl * 1000));
        }
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public RagCredentialContext verifiedIngressCredentials(String actor,
            com.bkanent.agent.authorization.AgentAuthorizationScope.Entry entry) {
        Lease lease = CURRENT.get();
        if (lease == null || lease.authorization != entry
                || entry != com.bkanent.agent.authorization.AgentAuthorizationScope.current())
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
        verify(lease, actor);
        return lease.credentials;
    }

    public synchronized void capture(String key, String originalUserId) {
        Lease current = CURRENT.get();
        if (current == null) return; // Metadata/userId alone must never mint credentials.
        if (current.authorization != null) {
            current.authorization.requireActor(originalUserId);
            current.authorization.requireRunAssociation();
            if (!key.equals(current.authorization.session().context().runId()))
                throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.CONTEXT_CONFLICT);
            // The execution key must match the independently bound run.
        }
        if (originalUserId == null || current.owner != null && !current.owner.equals(originalUserId)) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        Lease existing = leases.get(key);
        if (existing != null && !originalUserId.equals(existing.owner)) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        if (existing != null && existing.authorization != null && current.authorization != null
                && (!java.util.Objects.equals(existing.authorization.session().context().runId(), current.authorization.session().context().runId())
                || !java.util.Objects.equals(existing.authorization.session().context().sessionId(), current.authorization.session().context().sessionId()))) {
            throw new com.bkanent.common.authz.AuthzException(com.bkanent.common.authz.AuthzErrorCode.CONTEXT_CONFLICT);
        }
        leases.entrySet().removeIf(entry -> {
            Lease expired = entry.getValue();
            if (expired.expiresAt > clock.millis()) return false;
            if (expired.authorization != null) expired.authorization.releaseRegistration();
            return true;
        });
        if (!leases.containsKey(key) && leases.size() >= Math.min(10000, Math.max(1, properties.getMaxCredentialLeases())))
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        Lease replaced = leases.put(key, new Lease(originalUserId, current.credentials, current.expiresAt, current.authorization));
        if (current.authorization != null) current.authorization.markLeaseCaptured();
        if (replaced != null && replaced.authorization != null && replaced.authorization != current.authorization)
            replaced.authorization.releaseRegistration();
    }
    public synchronized AutoCloseable openLease(String key, String originalUserId) {
        Lease previous = CURRENT.get();
        Lease lease = leases.get(key);
        CURRENT.remove();
        if (lease != null) {
            if (!originalUserId.equals(lease.owner)) {
                if (previous != null) CURRENT.set(previous);
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            }
            CURRENT.set(lease);
        }
        var authorizationScope = com.bkanent.agent.authorization.AgentAuthorizationScope.open(
                lease == null ? null : lease.authorization);
        return () -> {
            try { authorizationScope.close(); }
            finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        };
    }
    public RagCredentialContext credentials(String originalUserId) {
        Lease lease = CURRENT.get();
        if (lease == null) throw new AgentRagAuthenticationException("AUTHENTICATION_REQUIRED");
        verify(lease, originalUserId);
        return lease.credentials;
    }
    public record ActorIdentity(com.bkanent.common.rpc.AuthenticatedPrincipal principal, long expiresAtEpochSecond,
                                com.bkanent.common.authz.AuthorizationContext authorizationContext,
                                com.bkanent.common.authz.AuthzFreshness authorizationFreshness) {
        public ActorIdentity(com.bkanent.common.rpc.AuthenticatedPrincipal principal, long expiresAtEpochSecond) {
            this(principal, expiresAtEpochSecond, null, null);
        }
    }
    public ActorIdentity verifiedIdentity(String originalUserId) {
        Lease lease = CURRENT.get();
        if (lease == null) throw new AgentRagAuthenticationException("AUTHENTICATION_REQUIRED");
        var principal = verify(lease, originalUserId);
        if (lease.authorization == null) return new ActorIdentity(principal, lease.expiresAt / 1000);
        var session = lease.authorization.session();
        return new ActorIdentity(principal, lease.expiresAt / 1000, session.context(), session.freshness());
    }
    private com.bkanent.common.rpc.AuthenticatedPrincipal verify(Lease lease, String originalUserId) {
        if (lease.expiresAt <= clock.millis()) throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        if (lease.owner != null && !lease.owner.equals(originalUserId)) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        if (lease.authorization != null) {
            String actor = lease.authorization.requireActor(originalUserId);
            try { return new com.bkanent.common.rpc.AuthenticatedPrincipal(Long.parseLong(actor)); }
            catch (NumberFormatException failure) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
        }
        try {
            var provider = auth.getIfAvailable();
            if (provider == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            var principal = provider.resolvePrincipal(lease.credentials.accessToken());
            if (principal == null || !String.valueOf(principal.userId()).equals(originalUserId))
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            return principal;
        } catch (RagException exception) { throw exception; }
        catch (RuntimeException exception) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    public long expiresAtEpochSecond(String originalUserId) {
        credentials(originalUserId);
        return CURRENT.get().expiresAt / 1000;
    }
    public java.util.function.Supplier<AutoCloseable> snapshot() {
        Lease captured = CURRENT.get();
        return () -> {
            Lease previous = CURRENT.get();
            if (captured == null) CURRENT.remove(); else CURRENT.set(captured);
            var authorizationScope = com.bkanent.agent.authorization.AgentAuthorizationScope.open(
                    captured == null ? null : captured.authorization);
            return () -> {
                try { authorizationScope.close(); }
                finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
            };
        };
    }
    public String currentUserId() {
        Lease lease = CURRENT.get();
        if (lease == null) throw new AgentRagAuthenticationException("AUTHENTICATION_REQUIRED");
        if (lease.authorization != null) {
            String actor = lease.authorization.requireActor(null);
            verify(lease, actor);
            return actor;
        }
        try {
            var principal = auth.getObject().resolvePrincipal(lease.credentials.accessToken());
            if (principal == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            String owner = String.valueOf(principal.userId()); verify(lease, owner); return owner;
        } catch (RagException ex) { throw ex; }
        catch (RuntimeException ex) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    public synchronized void discard(String key) {
        Lease removed = leases.remove(key);
        if (removed != null && removed.authorization != null) removed.authorization.releaseRegistration();
    }
    private static final class Lease {
        private final String owner;
        private final RagCredentialContext credentials;
        private final long expiresAt;
        private final com.bkanent.agent.authorization.AgentAuthorizationScope.Entry authorization;
        private Lease(String owner, RagCredentialContext credentials, long expiresAt) {
            this(owner, credentials, expiresAt, null);
        }
        private Lease(String owner, RagCredentialContext credentials, long expiresAt,
                      com.bkanent.agent.authorization.AgentAuthorizationScope.Entry authorization) {
            this.owner = owner; this.credentials = credentials; this.expiresAt = expiresAt;
            this.authorization = authorization;
        }
    }
}
