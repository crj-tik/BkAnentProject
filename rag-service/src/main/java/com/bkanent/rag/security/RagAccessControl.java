package com.bkanent.rag.security;

import com.bkanent.common.rag.*;
import com.bkanent.common.authz.AuthzResponse;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.rag.config.RagSecurityProperties;
import com.bkanent.rag.storage.RagSpaceDefinition;
import com.bkanent.rag.storage.RagSpaceRegistry;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/** Trusted key AND active authoritative role AND owner-signed scope; no user/service name shortcuts. */
public final class RagAccessControl {
    private final RagSpaceRegistry registry;
    private final RagSecurityProperties properties;
    private final Map<String, RagSigningKey> keys;
    private final Map<String, java.security.PublicKey> authzKeys;
    private final Supplier<RagPrincipalRpcService> auth;
    private final Clock clock;

    public RagAccessControl(RagSpaceRegistry registry, RagSecurityProperties properties,
            Supplier<RagPrincipalRpcService> auth, Clock clock) {
        this.registry = registry;
        this.properties = properties;
        this.keys = properties.signingKeys();
        this.authzKeys = properties.authzVerificationKeys();
        this.auth = auth;
        this.clock = clock;
    }

    public List<RagFilter> search(RagSearchRequest request, RagAuthEnvelope envelope,
            RagCredentialContext credentials, RagAuthorizationScope scope) {
        RagSpaceDefinition space = registry.require(request.spaceId());
        require(!space.candidateAuthorizationRequired()); // v2 memory spaces have no legacy/user-scope route.
        String hash = RagCanonicalHash.requestHash(request);
        verify(space, envelope, credentials, RagOperation.SEARCH, request.requestId(), hash);
        require(envelope.actorMode() == RagActorMode.USER);
        RagSigningKey owner = scope == null ? null : keys.get(scope.keyId());
        RagScopeSigner.verify(scope, owner, space.spaceId(), envelope.actorId(), RagOperation.SEARCH,
                hash, space.ownerDomain(), space.policyVersion(), clock.instant());
        require(space.allowedClients().getOrDefault(owner.callerService(), Set.of()).contains(RagOperation.SEARCH));
        if (properties.versionedAuthorizationEnabled()) require(scope.versionedAuthorization());
        else require(!scope.versionedAuthorization());
        AuthRagPrincipal principal = properties.versionedAuthorizationEnabled()
                ? versionedSearchPrincipal(envelope, scope) : principal(envelope, credentials);
        require(String.valueOf(principal.userId()).equals(envelope.actorId()));
        if (properties.versionedAuthorizationEnabled()) {
            require(principal.versionedAuthorization() && scope.versionedAuthorization());
            require(scope.permissionRevision() == principal.permissionRevision()
                    && scope.credentialEpoch() == principal.credentialEpoch()
                    && scope.businessAdmin() == principal.businessAdmin());
            String requiredOperation = requiredSearchPermission(space.spaceId(), principal.businessAdmin());
            require(requiredOperation != null && requiredOperation.equals(scope.operationCode())
                    && principal.hasPermission(requiredOperation));
        } else {
            require(!principal.versionedAuthorization() && scope != null && !scope.versionedAuthorization());
            require(Set.of("ADMIN", "BROKER").contains(principal.roleCode())
                    && space.searchRoles().contains(principal.roleCode()));
        }
        List<RagFilter> mandatory = new ArrayList<>();
        if ("listing.knowledge".equals(space.spaceId())) {
            if (scope.businessWideRead()) {
                require(scope.ownerId() == null && scope.allowedResourceIds().isEmpty());
                boolean businessAdmin = principal.versionedAuthorization()
                        ? principal.businessAdmin() : "ADMIN".equals(principal.roleCode());
                require(businessAdmin ? Boolean.FALSE.equals(scope.activeOnly()) : Boolean.TRUE.equals(scope.activeOnly()));
            } else {
                require(!scope.allowedResourceIds().isEmpty() && Boolean.TRUE.equals(scope.activeOnly()));
                requiredField(space, "listingId", RagValueType.STRING);
                mandatory.add(new RagFilter("listingId", RagFilterOperator.IN, RagValue.strings(scope.allowedResourceIds())));
            }
        } else if ("memory.preference".equals(space.spaceId())) {
            require(!scope.businessWideRead() && principal.userId().toString().equals(scope.ownerId())
                    && Boolean.TRUE.equals(scope.activeOnly()));
            // Current memory projection's trusted owner field is userId, never model-provided ownerId.
            requiredField(space, "userId", RagValueType.STRING);
            mandatory.add(new RagFilter("userId", RagFilterOperator.EQ, RagValue.string(principal.userId().toString())));
        } else if ("memory.constraint".equals(space.spaceId())) {
            require(!scope.businessWideRead() && scope.ownerId() == null
                    && scope.allowedResourceIds().isEmpty() && Boolean.TRUE.equals(scope.activeOnly()));
        } else {
            // General content is not public just because it has no per-resource scope.
            require(!scope.businessWideRead() && Boolean.TRUE.equals(scope.activeOnly()));
            if (properties.versionedAuthorizationEnabled()) {
                require("supervisor.knowledge".equals(space.spaceId()) && scope.allowedResourceIds().isEmpty()
                        && principal.userId().toString().equals(scope.ownerId()));
                requiredField(space, "ownerId", RagValueType.STRING);
                mandatory.add(new RagFilter("ownerId", RagFilterOperator.EQ,
                        RagValue.string(principal.userId().toString())));
            }
        }
        if (Boolean.TRUE.equals(scope.activeOnly())) {
            requiredField(space, "active", RagValueType.BOOLEAN);
            mandatory.add(new RagFilter("active", RagFilterOperator.EQ, RagValue.bool(true)));
        }
        return List.copyOf(mandatory);
    }

    /** Memory v2 has a separate, short-lived signed scope and never falls through legacy/user search. */
    public List<RagFilter> searchMemory(RagSearchRequest request, RagAuthEnvelope envelope,
                                        com.bkanent.common.rag.MemoryRagAuthorizationScope scope) {
        RagSpaceDefinition space = registry.require(request.spaceId());
        String hash = RagCanonicalHash.requestHash(request);
        RagSigningKey caller = verify(space, envelope, null, RagOperation.MEMORY_SEARCH, request.requestId(), hash);
        require(envelope.actorMode() == RagActorMode.SERVICE && "memory-service".equals(caller.callerService())
                && space.candidateAuthorizationRequired() && Objects.equals(space.ownerDomain(), caller.ownerDomain()));
        var type = com.bkanent.common.rag.MemoryRagRecordType.fromId(space.memoryRecordType());
        require(scope != null && scope.recordType() == type && space.memoryDomainId().equals(scope.domainId())
                && space.memoryAgentId().equals(scope.agentId()));
        com.bkanent.common.rag.MemoryRagScopeSigner.verify(scope, caller.keyId(), caller.secret(), request,
                scope.ownerId(), space.memoryDomainId(), space.memoryAgentId(), clock.instant());

        List<RagFilter> mandatory = new ArrayList<>();
        require(!scope.ownerId().isBlank());
        requiredField(space, "ownerId", RagValueType.STRING);
        requiredField(space, "active", RagValueType.BOOLEAN);
        mandatory.add(new RagFilter("ownerId", RagFilterOperator.EQ, RagValue.string(scope.ownerId())));
        mandatory.add(new RagFilter("active", RagFilterOperator.EQ, RagValue.bool(true)));
        if (type == com.bkanent.common.rag.MemoryRagRecordType.COLLABORATION) {
            require(!scope.grantIds().isEmpty());
            requiredField(space, "recipientDomainId", RagValueType.STRING);
            requiredField(space, "recipientAgentId", RagValueType.STRING);
            requiredField(space, "grantId", RagValueType.STRING);
            mandatory.add(new RagFilter("recipientDomainId", RagFilterOperator.EQ, RagValue.string(scope.domainId())));
            mandatory.add(new RagFilter("recipientAgentId", RagFilterOperator.EQ, RagValue.string(scope.agentId())));
            mandatory.add(new RagFilter("grantId", RagFilterOperator.IN, RagValue.strings(scope.grantIds())));
        } else {
            requiredField(space, "domainId", RagValueType.STRING);
            requiredField(space, "agentId", RagValueType.STRING);
            mandatory.add(new RagFilter("domainId", RagFilterOperator.EQ, RagValue.string(scope.domainId())));
            mandatory.add(new RagFilter("agentId", RagFilterOperator.EQ, RagValue.string(scope.agentId())));
        }
        return List.copyOf(mandatory);
    }

    public RagSpaceDefinition submit(RagIndexEvent event, RagAuthEnvelope envelope) {
        RagSpaceDefinition space = registry.require(event.spaceId());
        RagSigningKey caller = verify(space, envelope, null, RagOperation.SUBMIT, event.requestId(), RagCanonicalHash.requestHash(event));
        require(envelope.actorMode() == RagActorMode.SERVICE && Objects.equals(space.ownerDomain(), caller.ownerDomain()));
        return space;
    }

    /** Status has no credential parameter: only authenticated owner-domain service keys may read it. */
    public void status(RagStatusRequest request, RagAuthEnvelope envelope) {
        RagSpaceDefinition space = registry.require(request.spaceId());
        RagSigningKey caller = verify(space, envelope, null, RagOperation.GET_STATUS, request.requestId(), RagCanonicalHash.requestHash(request));
        require(envelope.actorMode() == RagActorMode.SERVICE && Objects.equals(space.ownerDomain(), caller.ownerDomain()));
    }

    /** Management requires a user, explicit caller/space grant and an authoritative active admin role. */
    public void admin(Object request, String spaceId, String requestId, RagOperation operation,
            RagAuthEnvelope envelope, RagCredentialContext credentials) {
        require(operation == RagOperation.INSPECT || operation == RagOperation.PROVISION || operation == RagOperation.REPLAY);
        RagSpaceDefinition space = registry.require(spaceId);
        verify(space, envelope, credentials, operation, requestId, RagCanonicalHash.requestHash(request));
        // Granting management operations to a domain writer never upgrades its SERVICE actor.
        require(envelope.actorMode() == RagActorMode.USER);
        AuthRagPrincipal principal = principal(envelope, credentials);
        if (properties.versionedAuthorizationEnabled()) {
            require(principal.versionedAuthorization() && principal.hasPermission("rag.space.manage"));
        } else {
            require(!principal.versionedAuthorization()
                    && "ADMIN".equals(principal.roleCode()) && space.adminRoles().contains(principal.roleCode()));
        }
    }

    private RagSigningKey verify(RagSpaceDefinition space, RagAuthEnvelope envelope, RagCredentialContext credentials,
            RagOperation operation, String requestId, String hash) {
        RagSigningKey key = envelope == null ? null : keys.get(envelope.keyId());
        RagRequestSigner.verify(envelope, hash, "rag-service", operation, requestId, credentials, key, clock.instant());
        require(space.allowedClients().getOrDefault(key.callerService(), Set.of()).contains(operation));
        return key;
    }

    private AuthRagPrincipal principal(RagAuthEnvelope original, RagCredentialContext credentials) {
        if (!properties.configured()) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        String digest = RagCanonicalHash.credentialDigest(credentials);
        long now = clock.instant().getEpochSecond();
        RagAuthEnvelope unsigned = new RagAuthEnvelope(1, properties.authRequestKeyId(), "auth-service", "rag-service",
                RagOperation.RESOLVE_PRINCIPAL, original.requestId(), digest, RagActorMode.USER, original.actorId(),
                now, Math.min(now + 60, original.expiresAtEpochSecond()), digest, null);
        RagAuthEnvelope signed = RagRequestSigner.sign(unsigned, RagSecurityProperties.bytes(properties.authRequestSecret()));
        AuthRagPrincipal principal;
        try { principal = auth.get().resolveRagPrincipal(credentials, signed); }
        catch (RagException failure) { throw failure; }
        catch (RuntimeException failure) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "authentication"); }
        if (principal == null || principal.userId() == null || principal.userId() <= 0 || !principal.accountActive()
                || properties.versionedAuthorizationEnabled() != principal.versionedAuthorization()
                || !principal.versionedAuthorization() && (principal.roleCode() == null
                || principal.roleCode().isBlank() || !principal.roleActive())
                || principal.attestation() == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        if (principal.expiresAtEpochSecond() <= clock.instant().getEpochSecond()) throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        RagSigningKey responseKey = new RagSigningKey(properties.authResponseKeyId(), "auth-service", null,
                Set.of(RagOperation.RESOLVE_PRINCIPAL_RESPONSE), RagSecurityProperties.bytes(properties.authResponseSecret()));
        RagRequestSigner.verify(principal.attestation(), RagCanonicalHash.principalHash(principal), "rag-service",
                RagOperation.RESOLVE_PRINCIPAL_RESPONSE, original.requestId(), credentials, responseKey, clock.instant());
        if (principal.attestation().actorMode() != RagActorMode.USER
                || !principal.userId().toString().equals(principal.attestation().actorId())
                || !principal.userId().toString().equals(original.actorId())) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        return principal;
    }

    /** Search consumes a request-bound Auth proof locally; it never calls Auth once per RAG recall. */
    private AuthRagPrincipal versionedSearchPrincipal(RagAuthEnvelope caller, RagAuthorizationScope scope) {
        long now = clock.instant().getEpochSecond();
        AuthRagPrincipal principal;
        if (scope.authPrincipal() != null) {
            principal = scope.authPrincipal();
            RagAuthEnvelope attestation = principal.attestation();
            if (attestation == null || attestation.audience() == null
                    || !spaceCallerMayResolve(scope.spaceId(), attestation.audience())) {
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            }
            RagSigningKey responseKey = new RagSigningKey(properties.authResponseKeyId(), "auth-service", null,
                    Set.of(RagOperation.RESOLVE_PRINCIPAL_RESPONSE), RagSecurityProperties.bytes(properties.authResponseSecret()));
            RagRequestSigner.verifyAttestation(attestation, RagCanonicalHash.principalHash(principal),
                    attestation.audience(), attestation.requestId(), String.valueOf(principal.userId()), responseKey,
                    clock.instant());
        } else {
            AuthzResponse response = scope.authorizationContext();
            try {
                // Re-run cross-field construction checks because the RPC serializer is not an authority.
                response = new AuthzResponse(response.snapshot(), response.context(), response.freshness());
                AuthzSignature.verifySnapshot(response.snapshot(), authzKeys, clock.instant());
                AuthzSignature.verifyFreshness(response.freshness(), authzKeys, clock.instant());
                AuthzSignature.verifyContext(response.context(), scope.issuerService(), authzKeys, clock.instant());
            } catch (RuntimeException invalid) {
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            }
            var context = response.context();
            if ((context.runId() == null) != (context.sessionId() == null)
                    || !String.valueOf(response.snapshot().actorId()).equals(caller.actorId())) {
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            }
            long expiresAt = Math.min(context.expiresAtEpochSecond(), context.runHardDeadlineEpochSecond());
            expiresAt = Math.min(expiresAt, response.freshness().notAfterEpochSecond());
            if (response.snapshot().grantDeadlineEpochSecond() != null) {
                expiresAt = Math.min(expiresAt, response.snapshot().grantDeadlineEpochSecond());
            }
            principal = new AuthRagPrincipal(Long.valueOf(context.actorId()), null, true, false, expiresAt,
                    AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION, context.permissionRevision(),
                    context.credentialEpoch(), response.snapshot().businessAdmin(), List.of(),
                    response.snapshot().permissions(), null);
            require(context.allowedOperations().contains(scope.operationCode()));
        }
        if (principal.userId() == null || principal.userId() <= 0 || !principal.accountActive()
                || !principal.versionedAuthorization() || principal.expiresAtEpochSecond() <= now
                || principal.permissionRevision() != scope.permissionRevision()
                || principal.credentialEpoch() != scope.credentialEpoch()
                || principal.businessAdmin() != scope.businessAdmin()
                || !String.valueOf(principal.userId()).equals(caller.actorId())) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        return principal;
    }

    private boolean spaceCallerMayResolve(String spaceId, String callerService) {
        return Set.of("agent-service", "listing-master-service", "memory-service", "rag-service",
                        "customer-service", "marketing-content-service", "business-service", "contract-service",
                        "settlement-service", "interview-service")
                .contains(callerService)
                && registry.require(spaceId).allowedClients().getOrDefault(callerService, Set.of())
                .contains(RagOperation.SEARCH);
    }

    private static String requiredSearchPermission(String spaceId, boolean businessAdmin) {
        return switch (spaceId) {
            case "listing.knowledge" -> businessAdmin ? "listing.read.all" : "listing.read";
            case "memory.preference" -> "memory.search";
            case "memory.constraint" -> "memory.constraint.read";
            case "supervisor.knowledge" -> "agent.rag.search";
            default -> null;
        };
    }

    private static void requiredField(RagSpaceDefinition space, String name, RagValueType type) {
        RagSpaceDefinition.FilterField field = space.fields().get(name);
        if (field == null || field.type() != type || !field.required()) throw new RagException(RagErrorCode.SPACE_NOT_READY, "authorizationSchema");
    }
    private static void require(boolean permitted) { if (!permitted) throw new RagException(RagErrorCode.FORBIDDEN); }
}
