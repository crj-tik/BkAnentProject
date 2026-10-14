package com.bkanent.rag.security;

import com.bkanent.common.rag.*;
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
    private final Supplier<RagPrincipalRpcService> auth;
    private final Clock clock;

    public RagAccessControl(RagSpaceRegistry registry, RagSecurityProperties properties,
            Supplier<RagPrincipalRpcService> auth, Clock clock) {
        this.registry = registry;
        this.properties = properties;
        this.keys = properties.signingKeys();
        this.auth = auth;
        this.clock = clock;
    }

    public List<RagFilter> search(RagSearchRequest request, RagAuthEnvelope envelope,
            RagCredentialContext credentials, RagAuthorizationScope scope) {
        RagSpaceDefinition space = registry.require(request.spaceId());
        String hash = RagCanonicalHash.requestHash(request);
        verify(space, envelope, credentials, RagOperation.SEARCH, request.requestId(), hash);
        require(envelope.actorMode() == RagActorMode.USER);
        AuthRagPrincipal principal = principal(envelope, credentials);
        require(Set.of("ADMIN", "BROKER").contains(principal.roleCode())
                && space.searchRoles().contains(principal.roleCode()));
        RagSigningKey owner = scope == null ? null : keys.get(scope.keyId());
        RagScopeSigner.verify(scope, owner, space.spaceId(), principal.userId().toString(), RagOperation.SEARCH,
                hash, space.ownerDomain(), space.policyVersion(), clock.instant());
        // The signing owner itself must be registered to perform the asserted operation in this space.
        require(space.allowedClients().getOrDefault(owner.callerService(), Set.of()).contains(RagOperation.SEARCH));
        List<RagFilter> mandatory = new ArrayList<>();
        if ("listing.knowledge".equals(space.spaceId())) {
            if (scope.businessWideRead()) {
                require(scope.ownerId() == null && scope.allowedResourceIds().isEmpty());
                require("ADMIN".equals(principal.roleCode())
                        ? Boolean.FALSE.equals(scope.activeOnly()) : Boolean.TRUE.equals(scope.activeOnly()));
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
        } else {
            // General/constraint content is not public just because it has no per-resource scope.
            require(!scope.businessWideRead() && Boolean.TRUE.equals(scope.activeOnly()));
        }
        if (Boolean.TRUE.equals(scope.activeOnly())) {
            requiredField(space, "active", RagValueType.BOOLEAN);
            mandatory.add(new RagFilter("active", RagFilterOperator.EQ, RagValue.bool(true)));
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
        require("ADMIN".equals(principal.roleCode()) && space.adminRoles().contains(principal.roleCode()));
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
        if (principal == null || principal.userId() == null || principal.userId() <= 0 || principal.roleCode() == null
                || principal.roleCode().isBlank() || !principal.accountActive() || !principal.roleActive()
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

    private static void requiredField(RagSpaceDefinition space, String name, RagValueType type) {
        RagSpaceDefinition.FilterField field = space.fields().get(name);
        if (field == null || field.type() != type || !field.required()) throw new RagException(RagErrorCode.SPACE_NOT_READY, "authorizationSchema");
    }
    private static void require(boolean permitted) { if (!permitted) throw new RagException(RagErrorCode.FORBIDDEN); }
}
