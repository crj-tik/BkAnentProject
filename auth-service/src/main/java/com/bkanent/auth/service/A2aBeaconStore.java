package com.bkanent.auth.service;

import com.bkanent.auth.entity.UserAccountEntity;
import com.bkanent.common.rag.*;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Bounded auth-owned opaque leases; every use rechecks the original token and current SQL facts. */
@Service
public class A2aBeaconStore {
    private final AuthTokenService tokens;
    private final UserAccountService users;
    private final UserManagementService roles;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Lease> leases = new HashMap<>();
    public A2aBeaconStore(AuthTokenService tokens, UserAccountService users, UserManagementService roles) {
        this.tokens = tokens; this.users = users; this.roles = roles;
    }
    public AuthRagPrincipal accessPrincipal(String token) {
        try {
            var principal = tokens.parse(token);
            if (principal == null) throw new RagException(tokens.isExpiredAccessToken(token)
                    ? RagErrorCode.AUTHENTICATION_EXPIRED : RagErrorCode.AUTHENTICATION_FAILED);
            if (!"access".equals(principal.tokenType())) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            UserAccountEntity account = users.getById(principal.userId());
            if (account == null || !Long.valueOf(principal.userId()).equals(account.getId())
                    || !Integer.valueOf(0).equals(account.getDeleted()) || !"ACTIVE".equalsIgnoreCase(account.getAccountStatus())
                    || !UserManagementService.isSupportedRole(account.getRoleCode()) || !roles.isActiveRole(account.getRoleCode()))
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            return new AuthRagPrincipal(account.getId(), account.getRoleCode(), true, true, principal.expiresAt());
        } catch (RagException ex) { throw ex; }
        catch (RuntimeException ex) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    public A2aBeacon issue(A2aBeaconBinding binding, RagCredentialContext credentials) {
        return issue(binding, credentials, Instant.now().getEpochSecond() + 300);
    }
    public synchronized A2aBeacon issue(A2aBeaconBinding binding, RagCredentialContext credentials, long leaseExpiry) {
        if (credentials == null || credentials.accessToken() == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        AuthRagPrincipal principal = accessPrincipal(credentials.accessToken());
        if (!principal.userId().toString().equals(binding.actorId())) throw new RagException(RagErrorCode.FORBIDDEN);
        long now = Instant.now().getEpochSecond();
        leases.values().removeIf(lease -> lease.expiresAt <= now);
        if (leases.size() >= 10000) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String reference = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expiry = Math.min(leaseExpiry, Math.min(now + 300, principal.expiresAtEpochSecond()));
        if (expiry <= now) throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        leases.put(reference, new Lease(binding, credentials.accessToken(), expiry));
        return new A2aBeacon(reference, expiry);
    }
    public synchronized AuthRagPrincipal resolve(A2aBeaconBinding binding, RagCredentialContext credentials, String caller) {
        Lease lease = lease(credentials);
        if (!lease.binding.equals(binding) || !binding.audience().equals(caller)) throw new RagException(RagErrorCode.FORBIDDEN);
        return current(lease);
    }
    public synchronized AuthRagPrincipal principal(RagCredentialContext credentials, String caller) {
        if (credentials.accessToken() != null) return accessPrincipal(credentials.accessToken());
        Lease lease = lease(credentials);
        if (!principalConsumer(lease.binding, caller)) throw new RagException(RagErrorCode.FORBIDDEN);
        return current(lease);
    }
    private static boolean principalConsumer(A2aBeaconBinding binding, String caller) {
        if (binding.audience().equals(caller)) return true;
        // A principal-only response never grants business scope. Domain READ/RAG still verify
        // the independently signed caller envelope, operation, owner and exact request hash.
        if (!"agent-service".equals(binding.callerService())) return false;
        return switch (binding.audience()) {
            case "listing-master-service", "customer-service", "marketing-content-service" ->
                    java.util.Set.of("listing-master-service", "memory-service", "rag-service").contains(caller);
            default -> false;
        };
    }
    private Lease lease(RagCredentialContext credentials) {
        if (credentials == null || credentials.beacon() == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        Lease lease = leases.get(credentials.beacon());
        if (lease == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        if (lease.expiresAt <= Instant.now().getEpochSecond()) {
            leases.remove(credentials.beacon()); throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        }
        return lease;
    }
    private AuthRagPrincipal current(Lease lease) {
        AuthRagPrincipal principal = accessPrincipal(lease.token);
        if (!principal.userId().toString().equals(lease.binding.actorId())) throw new RagException(RagErrorCode.FORBIDDEN);
        return new AuthRagPrincipal(principal.userId(), principal.roleCode(), true, true,
                Math.min(principal.expiresAtEpochSecond(), lease.expiresAt));
    }
    private record Lease(A2aBeaconBinding binding, String token, long expiresAt) {
        @Override public String toString() { return "Lease[REDACTED]"; }
    }
}
