package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.listing.config.ListingRagProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Resolves current account/role facts through independently authenticated auth RPC. */
@Service
public class ListingRagIdentity {
    public static final String SERVICE = "listing-master-service";
    public static final String SPACE = "listing.knowledge";
    private final ListingRagProperties properties;
    private final ObjectProvider<RagPrincipalRpcService> auth;
    public ListingRagIdentity(ListingRagProperties properties,
                              @Qualifier("ragPrincipalRpcService") ObjectProvider<RagPrincipalRpcService> auth) {
        this.properties = properties;
        this.auth = auth;
    }
    public AuthRagPrincipal resolve(RagCredentialContext credentials) {
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            return scopedPrincipal();
        }
        if (credentials == null) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        RagPrincipalRpcService provider = auth.getIfAvailable();
        if (provider == null) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
        String requestId = UUID.randomUUID().toString();
        String digest = RagCanonicalHash.credentialDigest(credentials);
        RagAuthEnvelope request = sign("auth-service", RagOperation.RESOLVE_PRINCIPAL, requestId, digest,
                RagActorMode.SERVICE, SERVICE, credentials);
        AuthRagPrincipal principal;
        try { principal = provider.resolveRagPrincipal(credentials, request); }
        catch (RagException e) { throw e; }
        catch (RuntimeException e) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
        if (principal == null || principal.attestation() == null || principal.userId() == null || principal.userId() <= 0) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        RagRequestSigner.verify(principal.attestation(), RagCanonicalHash.principalHash(principal), SERVICE,
                RagOperation.RESOLVE_PRINCIPAL_RESPONSE, requestId, credentials,
                new RagSigningKey(properties.getAuthResponseKeyId(), "auth-service", null,
                        Set.of(RagOperation.RESOLVE_PRINCIPAL_RESPONSE), bytes(properties.getAuthResponseSecret())), Instant.now());
        if (principal.attestation().actorMode() != RagActorMode.USER
                || !String.valueOf(principal.userId()).equals(principal.attestation().actorId())
                || !principal.accountActive() || !principal.versionedAuthorization() && !principal.roleActive()) {
            throw new RagException(RagErrorCode.FORBIDDEN);
        }
        if (principal.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) {
            throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        }
        if (properties.isVersionedAuthorizationRequired() && !principal.versionedAuthorization()) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        requireCurrent(principal);
        return principal;
    }
    public AuthRagPrincipal requireReader(RagCredentialContext credentials) {
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            String operation = com.bkanent.common.authz.AuthzRequestScope.businessAdmin()
                    ? "listing.read.all" : "listing.read";
            com.bkanent.common.authz.AuthzRequestScope.requireOperation(operation);
            return scopedPrincipal();
        }
        AuthRagPrincipal p = resolve(credentials);
        if (p.versionedAuthorization() ? !p.hasPermission("listing.read")
                : !properties.getReadRoleCodes().contains(p.roleCode())) { throw new RagException(RagErrorCode.FORBIDDEN); }
        return p;
    }
    public AuthRagPrincipal requireAdmin(RagCredentialContext credentials) {
        if (com.bkanent.common.authz.AuthzRequestScope.present()) {
            com.bkanent.common.authz.AuthzRequestScope.requireOperation("listing.index.manage");
            if (!com.bkanent.common.authz.AuthzRequestScope.businessAdmin()) throw new RagException(RagErrorCode.FORBIDDEN);
            return scopedPrincipal();
        }
        AuthRagPrincipal p = resolve(credentials);
        if (p.versionedAuthorization() ? !p.hasPermission("agent.vector-store.manage")
                : !"ADMIN".equals(p.roleCode()) || !properties.getAdminRoleCodes().contains(p.roleCode())) {
            throw new RagException(RagErrorCode.FORBIDDEN);
        }
        return p;
    }

    private AuthRagPrincipal scopedPrincipal() {
        var response = com.bkanent.common.authz.AuthzRequestScope.requireCurrent();
        var context = response.context();
        long expires = Math.min(context.expiresAtEpochSecond(),
                Math.min(context.runHardDeadlineEpochSecond(), response.freshness().notAfterEpochSecond()));
        if (response.snapshot().grantDeadlineEpochSecond() != null) {
            expires = Math.min(expires, response.snapshot().grantDeadlineEpochSecond());
        }
        return new AuthRagPrincipal(Long.parseLong(context.actorId()), "SIGNED", true, true, expires,
                AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION, context.permissionRevision(),
                context.credentialEpoch(), response.snapshot().businessAdmin(),
                response.snapshot().businessAdmin() ? java.util.List.of("BUSINESS_ADMIN") : java.util.List.of("BROKER"),
                response.snapshot().permissions(), null);
    }
    public static void requireCurrent(AuthRagPrincipal principal) {
        if (principal == null || principal.userId() == null || principal.userId() <= 0
                || !principal.accountActive() || !principal.versionedAuthorization() && !principal.roleActive()) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        if (principal.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) {
            throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        }
        if (!principal.versionedAuthorization() && !"ADMIN".equals(principal.roleCode()) && !"BROKER".equals(principal.roleCode())) {
            throw new RagException(RagErrorCode.FORBIDDEN);
        }
    }
    public static boolean activeOnly(AuthRagPrincipal principal) {
        requireCurrent(principal);
        return principal.versionedAuthorization() ? !principal.businessAdmin() : !"ADMIN".equals(principal.roleCode());
    }
    public static boolean canRead(AuthRagPrincipal principal, com.bkanent.listing.entity.ListingEntity entity) {
        return entity != null && Integer.valueOf(0).equals(entity.getDeleted())
                && (!activeOnly(principal) || "ON_SALE".equals(entity.getStatus()));
    }
    public RagAuthEnvelope sign(String audience, RagOperation operation, String requestId, String hash,
                                RagActorMode mode, String actor, RagCredentialContext credentials) {
        long now = Instant.now().getEpochSecond();
        return RagRequestSigner.sign(new RagAuthEnvelope(1, properties.getKeyId(), audience, SERVICE, operation,
                requestId, hash, mode, actor, now, now + 60,
                credentials == null ? null : RagCanonicalHash.credentialDigest(credentials), null), bytes(properties.getSecret()));
    }
    public void verifyCaller(Object request, String requestId, RagOperation operation,
                             RagAuthEnvelope envelope, RagCredentialContext credentials) {
        if (envelope == null) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
        ListingRagProperties.TrustedCaller caller = properties.getTrustedCallers().get(envelope.keyId());
        if (caller == null) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
        RagRequestSigner.verify(envelope, RagCanonicalHash.requestHash(request), SERVICE, operation, requestId,
                credentials, new RagSigningKey(envelope.keyId(), caller.getCallerService(), null,
                        Set.of(RagOperation.SEARCH, RagOperation.READ, RagOperation.SUBMIT), bytes(caller.getSecret())), Instant.now());
    }

    public com.bkanent.common.authz.AuthzRequestScope.Scope openAuthorizedReadScope(
            com.bkanent.common.rag.RagAuthorizedListingReadRequest request, RagAuthEnvelope envelope,
            com.bkanent.common.authz.AuthzResponse authorization,
            java.util.Map<String, java.security.PublicKey> authorityKeys) {
        try {
            if (request == null || authorization == null || authorityKeys == null || authorityKeys.isEmpty()) {
                throw new com.bkanent.common.authz.AuthzException(
                        com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
            }
            verifyCaller(request, request.request().requestId(), RagOperation.READ, envelope, null);
            var context = authorization.context();
            if (envelope.actorMode() != RagActorMode.USER
                    || !context.audience().equals(envelope.callerService())
                    || !context.actorId().equals(envelope.actorId())
                    || !request.authorizationEvidenceHash().equals(
                            com.bkanent.common.authz.AuthzCanonicalHash.signedResponseHash(authorization))) {
                throw new com.bkanent.common.authz.AuthzException(
                        com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_FAILED);
            }
            var scope = com.bkanent.common.authz.AuthzRequestScope.verifyAndOpenForwarded(authorization,
                    envelope.callerService(), authorityKeys, Instant.now());
            try {
                String operationCode = authorization.snapshot().businessAdmin() ? "listing.read.all" : "listing.read";
                com.bkanent.common.authz.AuthzRequestScope.requireOperation(operationCode);
                return scope;
            } catch (RuntimeException failure) {
                scope.close();
                throw failure;
            }
        } catch (com.bkanent.common.authz.AuthzException failure) {
            throw toRagException(failure);
        }
    }

    private static RagException toRagException(com.bkanent.common.authz.AuthzException failure) {
        RagErrorCode code = switch (failure.code()) {
            case PERMISSION_DENIED -> RagErrorCode.FORBIDDEN;
            case EXPIRED -> RagErrorCode.AUTHENTICATION_EXPIRED;
            case INVALID_ARGUMENT -> RagErrorCode.INVALID_ARGUMENT;
            case AUTHORIZATION_UNAVAILABLE -> RagErrorCode.DEPENDENCY_UNAVAILABLE;
            case CONTEXT_CONFLICT, REPLAY_DETECTED, AUTHENTICATION_REQUIRED, AUTHENTICATION_FAILED ->
                    RagErrorCode.AUTHENTICATION_FAILED;
        };
        return new RagException(code);
    }
    static byte[] bytes(String secret) {
        if (secret == null || secret.isBlank()) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
        return secret.getBytes(StandardCharsets.UTF_8);
    }
}
