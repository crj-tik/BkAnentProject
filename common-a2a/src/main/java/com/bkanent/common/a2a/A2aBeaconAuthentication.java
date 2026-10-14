package com.bkanent.common.a2a;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.A2aBeaconRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.core.env.Environment;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Existing service envelope scheme; per-service environment keys, never gateway markers. */
public final class A2aBeaconAuthentication {
    @DubboReference(check = false) private A2aBeaconRpcService auth;
    private final Environment environment;
    public A2aBeaconAuthentication(Environment environment) { this.environment = environment; }
    public String service() { return environment.getRequiredProperty("spring.application.name"); }
    private String keyId() { return environment.getRequiredProperty("a2a.auth.key-id"); }
    private byte[] secret(String property) {
        String text = environment.getProperty(property);
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length < 32) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        return text.getBytes(StandardCharsets.UTF_8);
    }
    public A2aBeacon issue(A2aBeaconBinding binding, RagCredentialContext credentials, long leaseExpiry) {
        long now = Instant.now().getEpochSecond();
        if (leaseExpiry <= now) throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        RagAuthEnvelope request = RagRequestSigner.sign(new RagAuthEnvelope(1, keyId(), "auth-service", service(),
                RagOperation.ISSUE_BEACON, UUID.randomUUID().toString(), RagCanonicalHash.requestHash(binding),
                RagActorMode.SERVICE, service(), now, Math.min(now + 60, leaseExpiry),
                RagCanonicalHash.credentialDigest(credentials), null), secret("a2a.auth.signing-secret"));
        try { return auth.issue(binding, credentials, request); }
        catch (RagException ex) { throw ex; }
        catch (RuntimeException ex) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    public AuthRagPrincipal resolve(A2aBeaconBinding binding, RagCredentialContext credentials) {
        RagAuthEnvelope request = envelope(binding, credentials, RagOperation.RESOLVE_BEACON);
        AuthRagPrincipal principal;
        try { principal = auth.resolve(binding, credentials, request); }
        catch (RagException ex) { throw ex; }
        catch (RuntimeException ex) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
        if (principal == null || principal.attestation() == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        RagRequestSigner.verify(principal.attestation(), RagCanonicalHash.principalHash(principal), service(),
                RagOperation.RESOLVE_PRINCIPAL_RESPONSE, request.requestId(), credentials,
                new RagSigningKey(environment.getRequiredProperty("a2a.auth.response-key-id"), "auth-service", null,
                        Set.of(RagOperation.RESOLVE_PRINCIPAL_RESPONSE), secret("a2a.auth.response-secret")), Instant.now());
        if (!principal.accountActive() || !principal.roleActive() || !Set.of("ADMIN", "BROKER").contains(principal.roleCode())
                || !binding.actorId().equals(principal.userId().toString())) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        return principal;
    }
    private RagAuthEnvelope envelope(A2aBeaconBinding binding, RagCredentialContext credentials, RagOperation operation) {
        long now = Instant.now().getEpochSecond();
        return RagRequestSigner.sign(new RagAuthEnvelope(1, keyId(), "auth-service", service(), operation,
                UUID.randomUUID().toString(), RagCanonicalHash.requestHash(binding), RagActorMode.SERVICE,
                service(), now, now + 60, RagCanonicalHash.credentialDigest(credentials), null), secret("a2a.auth.signing-secret"));
    }
}
