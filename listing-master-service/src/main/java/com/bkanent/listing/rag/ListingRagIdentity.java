package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.listing.config.ListingRagProperties;
import org.springframework.beans.factory.ObjectProvider;
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
    public ListingRagIdentity(ListingRagProperties properties, ObjectProvider<RagPrincipalRpcService> auth) {
        this.properties = properties;
        this.auth = auth;
    }
    public AuthRagPrincipal resolve(RagCredentialContext credentials) {
        if (credentials == null || credentials.accessToken() == null || credentials.accessToken().isBlank()) {
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
        if (principal == null || principal.attestation() == null || principal.userId() == null) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        RagRequestSigner.verify(principal.attestation(), RagCanonicalHash.principalHash(principal), SERVICE,
                RagOperation.RESOLVE_PRINCIPAL_RESPONSE, requestId, credentials,
                new RagSigningKey(properties.getAuthResponseKeyId(), "auth-service", null,
                        Set.of(RagOperation.RESOLVE_PRINCIPAL_RESPONSE), bytes(properties.getAuthResponseSecret())), Instant.now());
        if (principal.attestation().actorMode() != RagActorMode.USER
                || !String.valueOf(principal.userId()).equals(principal.attestation().actorId())
                || !principal.accountActive() || !principal.roleActive()) {
            throw new RagException(RagErrorCode.FORBIDDEN);
        }
        if (principal.expiresAtEpochSecond() <= Instant.now().getEpochSecond()) {
            throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        }
        return principal;
    }
    public AuthRagPrincipal requireReader(RagCredentialContext credentials) {
        AuthRagPrincipal p = resolve(credentials);
        if (!properties.getReadRoleCodes().contains(p.roleCode())) { throw new RagException(RagErrorCode.FORBIDDEN); }
        return p;
    }
    public AuthRagPrincipal requireAdmin(RagCredentialContext credentials) {
        AuthRagPrincipal p = resolve(credentials);
        if (!properties.getAdminRoleCodes().contains(p.roleCode())) { throw new RagException(RagErrorCode.FORBIDDEN); }
        return p;
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
                        Set.of(RagOperation.SEARCH, RagOperation.SUBMIT), bytes(caller.getSecret())), Instant.now());
    }
    static byte[] bytes(String secret) {
        if (secret == null || secret.isBlank()) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
        return secret.getBytes(StandardCharsets.UTF_8);
    }
}
