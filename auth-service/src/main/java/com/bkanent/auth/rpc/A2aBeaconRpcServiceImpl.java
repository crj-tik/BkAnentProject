package com.bkanent.auth.rpc;

import com.bkanent.auth.config.AuthA2aIdentityProperties;
import com.bkanent.auth.service.A2aBeaconStore;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.A2aBeaconRpcService;
import com.bkanent.common.security.ServiceIdentityCrypto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.dubbo.config.annotation.DubboService;

import java.time.Instant;

@DubboService
public class A2aBeaconRpcServiceImpl implements A2aBeaconRpcService {
    private static final String VERIFICATION_COUNTER = "service.identity.signature.verified";
    private static final String A2A_AUTH_PROTOCOL = "a2a-auth";
    private final A2aBeaconStore store;
    private final AuthA2aIdentityProperties a2aProperties;
    private final Counter ed25519Verified;

    public A2aBeaconRpcServiceImpl(A2aBeaconStore store, AuthA2aIdentityProperties a2aProperties,
                                   MeterRegistry meterRegistry) {
        this.store = store;
        this.a2aProperties = a2aProperties;
        this.ed25519Verified = meterRegistry.counter(VERIFICATION_COUNTER, "protocol", A2A_AUTH_PROTOCOL,
                "algorithm", ServiceIdentityCrypto.ED25519);
    }

    @Override
    public A2aBeacon issue(A2aBeaconBinding binding, RagCredentialContext credentials, RagAuthEnvelope authentication) {
        verify(binding, credentials, authentication, RagOperation.ISSUE_BEACON);
        if (!binding.callerService().equals(authentication.callerService())
                || !"agent-service".equals(binding.callerService())) {
            throw new RagException(RagErrorCode.FORBIDDEN);
        }
        return store.issue(binding, credentials, binding.credentialLeaseExpiresAtEpochSecond());
    }

    @Override
    public AuthRagPrincipal resolve(A2aBeaconBinding binding, RagCredentialContext credentials,
                                    RagAuthEnvelope authentication) {
        verify(binding, credentials, authentication, RagOperation.RESOLVE_BEACON);
        AuthRagPrincipal principal = store.resolve(binding, credentials, authentication.callerService());
        long now = Instant.now().getEpochSecond();
        String responseKeyId = a2aProperties.getResponseKeyId();
        if (responseKeyId == null || responseKeyId.isBlank()) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        RagAuthEnvelope unsigned = new RagAuthEnvelope(1, responseKeyId, authentication.callerService(),
                "auth-service", RagOperation.RESOLVE_PRINCIPAL_RESPONSE, authentication.requestId(),
                RagCanonicalHash.principalHash(principal), RagActorMode.USER, principal.userId().toString(), now,
                Math.min(authentication.expiresAtEpochSecond(), principal.expiresAtEpochSecond()),
                RagCanonicalHash.credentialDigest(credentials), null);
        RagAuthEnvelope response;
        try {
            response = A2aAuthSignature.sign(unsigned, a2aProperties.responseSigningKey());
        } catch (RuntimeException exception) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        return principal.withAttestation(response);
    }

    private void verify(A2aBeaconBinding binding, RagCredentialContext credentials, RagAuthEnvelope auth,
                        RagOperation operation) {
        if (binding == null || auth == null || auth.contractVersion() != A2aAuthSignature.ED25519_VERSION
                || !ServiceIdentityCrypto.ED25519.equals(auth.algorithm())) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }

        // The source service signs ISSUE; the bound target service signs RESOLVE.
        String expectedSigner = operation == RagOperation.ISSUE_BEACON
                ? binding.callerService() : binding.audience();
        var trusted = a2aProperties.getTrustedIdentities().get(auth.keyId());
        if (trusted == null || trusted.getPublicKey() == null || trusted.getPublicKey().isBlank()
                || !expectedSigner.equals(trusted.getCallerService())) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        try {
            A2aAuthSignature.verify(auth, RagCanonicalHash.requestHash(binding), "auth-service", operation,
                    auth.requestId(), expectedSigner, auth.keyId(), credentials, trusted.getAllowedOperations(),
                    ServiceIdentityCrypto.decodePublicKey(trusted.getPublicKey()), Instant.now());
        } catch (IllegalArgumentException exception) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        ed25519Verified.increment();
    }
}
