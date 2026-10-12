package com.bkanent.memory.service;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.memory.config.MemoryRagProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class MemoryRagSecurity {
    private final MemoryRagProperties properties;
    private final RagPrincipalRpcService principals;
    public MemoryRagSecurity(MemoryRagProperties properties, RagPrincipalRpcService principals) {
        this.properties = properties;
        this.principals = principals;
    }

    public AuthRagPrincipal authenticate(RagCredentialContext credentials) {
        String id = UUID.randomUUID().toString();
        String hash = RagCanonicalHash.credentialDigest(credentials);
        RagAuthEnvelope request = sign("auth-service", RagOperation.RESOLVE_PRINCIPAL, id, hash, RagActorMode.SERVICE, "memory-service", hash);
        AuthRagPrincipal principal;
        try { principal = principals.resolveRagPrincipal(credentials, request); }
        catch (RagException ex) { throw ex; }
        catch (RuntimeException ex) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
        if (principal == null || principal.attestation() == null || principal.userId() == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        RagSigningKey key = new RagSigningKey(properties.getAuthKeyId(), "auth-service", null, Set.of(RagOperation.RESOLVE_PRINCIPAL_RESPONSE), secret(properties.getAuthSigningSecret()));
        RagRequestSigner.verify(principal.attestation(), RagCanonicalHash.principalHash(principal), "memory-service", RagOperation.RESOLVE_PRINCIPAL_RESPONSE, id, credentials, key, Instant.now());
        if (!principal.accountActive() || !principal.roleActive() || !principal.userId().toString().equals(principal.attestation().actorId())) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        if (principal.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        return principal;
    }

    public AuthRagPrincipal verifyCaller(Object business, String id, RagAuthEnvelope authentication, RagCredentialContext credentials) {
        if (authentication == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        var caller = properties.getTrustedCallers().get(authentication.keyId());
        if (caller == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        RagSigningKey key = new RagSigningKey(authentication.keyId(), caller.getCallerService(), null, Set.of(RagOperation.SEARCH), secret(caller.getSecret()));
        RagRequestSigner.verify(authentication, RagCanonicalHash.requestHash(business), "memory-service", RagOperation.SEARCH, id, credentials, key, Instant.now());
        if (authentication.actorMode() != RagActorMode.USER) throw new RagException(RagErrorCode.FORBIDDEN);
        AuthRagPrincipal principal = authenticate(credentials);
        if (!principal.userId().toString().equals(authentication.actorId())) throw new RagException(RagErrorCode.FORBIDDEN);
        return principal;
    }

    public void requireOwner(AuthRagPrincipal principal, String ownerId) {
        if (!principal.userId().toString().equals(ownerId)) throw new RagException(RagErrorCode.FORBIDDEN);
    }

    public void requireConstraintRead(AuthRagPrincipal principal) {
        if (!properties.getConstraintReadRoles().contains(principal.roleCode())) throw new RagException(RagErrorCode.FORBIDDEN);
    }

    public void requireAdmin(AuthRagPrincipal principal) {
        if (!Set.of("ADMIN", "MANAGER").contains(principal.roleCode()) || !properties.getConstraintAdminRoles().contains(principal.roleCode())) throw new RagException(RagErrorCode.FORBIDDEN);
    }

    public RagAuthorizationScope scope(RagSearchRequest request, AuthRagPrincipal principal) {
        String owner = null;
        if (MemoryRagProjectionService.PREFERENCE.equals(request.spaceId())) owner = principal.userId().toString();
        else if (MemoryRagProjectionService.CONSTRAINT.equals(request.spaceId())) requireConstraintRead(principal);
        else throw new RagException(RagErrorCode.FORBIDDEN);
        long now = Instant.now().getEpochSecond();
        return RagScopeSigner.sign(new RagAuthorizationScope(1, properties.getKeyId(), "memory-service", "memory-service", request.spaceId(), principal.userId().toString(), RagOperation.SEARCH, RagCanonicalHash.requestHash(request), properties.getPolicyVersion(), now, Math.min(now + 60, principal.expiresAtEpochSecond()), owner, List.of(), true, null), secret(properties.getSigningSecret()));
    }

    public RagAuthEnvelope searchAuthentication(RagSearchRequest request, AuthRagPrincipal principal, RagCredentialContext credentials) {
        return sign("rag-service", RagOperation.SEARCH, request.requestId(), RagCanonicalHash.requestHash(request), RagActorMode.USER, principal.userId().toString(), RagCanonicalHash.credentialDigest(credentials));
    }

    public RagAuthEnvelope serviceAuthentication(RagOperation op, String id, Object request) {
        return sign("rag-service", op, id, RagCanonicalHash.requestHash(request), RagActorMode.SERVICE, "memory-service", null);
    }

    private RagAuthEnvelope sign(String audience, RagOperation op, String id, String hash, RagActorMode mode, String actor, String digest) {
        long now = Instant.now().getEpochSecond();
        return RagRequestSigner.sign(new RagAuthEnvelope(1, properties.getKeyId(), audience, "memory-service", op, id, hash, mode, actor, now, now + 60, digest, null), secret(properties.getSigningSecret()));
    }

    private static byte[] secret(String value) {
        if (value == null || value.isBlank()) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
