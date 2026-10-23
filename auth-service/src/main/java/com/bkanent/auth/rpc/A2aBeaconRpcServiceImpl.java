package com.bkanent.auth.rpc;

import com.bkanent.auth.config.AuthRagRpcProperties;
import com.bkanent.auth.config.AuthA2aIdentityProperties;
import com.bkanent.auth.service.A2aBeaconStore;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.A2aBeaconRpcService;
import com.bkanent.common.security.ServiceIdentityCrypto;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.dubbo.config.annotation.DubboService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

@DubboService
public class A2aBeaconRpcServiceImpl implements A2aBeaconRpcService {
    private final A2aBeaconStore store;
    private final AuthRagRpcProperties properties;
    private final AuthA2aIdentityProperties a2aProperties;
    private final MeterRegistry meterRegistry;
    public A2aBeaconRpcServiceImpl(A2aBeaconStore store, AuthRagRpcProperties properties,
                                   AuthA2aIdentityProperties a2aProperties, MeterRegistry meterRegistry) {
        this.store = store; this.properties = properties; this.a2aProperties = a2aProperties;
        this.meterRegistry = meterRegistry;
    }
    @Override public A2aBeacon issue(A2aBeaconBinding binding, RagCredentialContext credentials, RagAuthEnvelope authentication) {
        verify(binding, credentials, authentication, RagOperation.ISSUE_BEACON);
        if (!binding.callerService().equals(authentication.callerService()) || !"agent-service".equals(binding.callerService()))
            throw new RagException(RagErrorCode.FORBIDDEN);
        return store.issue(binding, credentials, binding.credentialLeaseExpiresAtEpochSecond());
    }
    @Override public AuthRagPrincipal resolve(A2aBeaconBinding binding, RagCredentialContext credentials, RagAuthEnvelope authentication) {
        verify(binding, credentials, authentication, RagOperation.RESOLVE_BEACON);
        AuthRagPrincipal principal = store.resolve(binding, credentials, authentication.callerService());
        long now = Instant.now().getEpochSecond();
        RagAuthEnvelope response;
        if (A2aAuthSignature.ED25519_VERSION == authentication.contractVersion()
                && ServiceIdentityCrypto.ED25519.equals(authentication.algorithm())) {
            String responseKeyId = a2aProperties.getResponseKeyId();
            if (responseKeyId == null || responseKeyId.isBlank()) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            RagAuthEnvelope unsigned = new RagAuthEnvelope(1, responseKeyId, authentication.callerService(), "auth-service",
                    RagOperation.RESOLVE_PRINCIPAL_RESPONSE, authentication.requestId(), RagCanonicalHash.principalHash(principal),
                    RagActorMode.USER, principal.userId().toString(), now,
                    Math.min(authentication.expiresAtEpochSecond(), principal.expiresAtEpochSecond()),
                    RagCanonicalHash.credentialDigest(credentials), null);
            try { response = A2aAuthSignature.sign(unsigned, a2aProperties.responseSigningKey()); }
            catch (RuntimeException exception) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
        } else {
            if (properties.getResponseKeyId() == null || properties.getResponseSecret() == null
                    || properties.getResponseSecret().getBytes(StandardCharsets.UTF_8).length < 32)
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            response = RagRequestSigner.sign(new RagAuthEnvelope(1, properties.getResponseKeyId(),
                    authentication.callerService(), "auth-service", RagOperation.RESOLVE_PRINCIPAL_RESPONSE,
                    authentication.requestId(), RagCanonicalHash.principalHash(principal), RagActorMode.USER,
                    principal.userId().toString(), now, Math.min(authentication.expiresAtEpochSecond(), principal.expiresAtEpochSecond()),
                    RagCanonicalHash.credentialDigest(credentials), null), properties.getResponseSecret().getBytes(StandardCharsets.UTF_8));
        }
        return new AuthRagPrincipal(principal.userId(), principal.roleCode(), true, true, principal.expiresAtEpochSecond(), response);
    }
    private void verify(A2aBeaconBinding binding, RagCredentialContext credentials, RagAuthEnvelope auth, RagOperation operation) {
        if (binding == null || auth == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        if (A2aAuthSignature.ED25519_VERSION == auth.contractVersion()
                && ServiceIdentityCrypto.ED25519.equals(auth.algorithm())) {
            var trusted = a2aProperties.getTrustedIdentities().get(auth.keyId());
            if (trusted == null || trusted.getPublicKey() == null || trusted.getPublicKey().isBlank()
                    || !binding.callerService().equals(trusted.getCallerService())) {
                throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            }
            try {
                A2aAuthSignature.verify(auth, RagCanonicalHash.requestHash(binding), "auth-service", operation,
                        auth.requestId(), binding.callerService(), auth.keyId(), credentials, trusted.getAllowedOperations(),
                        ServiceIdentityCrypto.decodePublicKey(trusted.getPublicKey()), Instant.now());
            } catch (IllegalArgumentException exception) { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }
            meterRegistry.counter("service.identity.signature.verified", "protocol", "a2a-auth",
                    "algorithm", ServiceIdentityCrypto.ED25519).increment();
            return;
        }
        if (auth.contractVersion() != RagContract.VERSION
                || (auth.algorithm() != null && !RagAuthEnvelope.HMAC_SHA256.equals(auth.algorithm()))) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        var trusted = properties.getTrustedKeys().get(auth.keyId());
        if (trusted == null || trusted.getSecret() == null || trusted.getSecret().getBytes(StandardCharsets.UTF_8).length < 32)
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        RagRequestSigner.verify(auth, RagCanonicalHash.requestHash(binding), "auth-service", operation,
                auth.requestId(), credentials, new RagSigningKey(auth.keyId(), trusted.getCallerService(), null,
                Set.of(operation), trusted.getSecret().getBytes(StandardCharsets.UTF_8)), Instant.now());
        meterRegistry.counter("service.identity.signature.verified", "protocol", "a2a-auth",
                "algorithm", RagAuthEnvelope.HMAC_SHA256).increment();
    }
}
