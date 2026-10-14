package com.bkanent.auth.rpc;

import com.bkanent.auth.config.AuthRagRpcProperties;
import com.bkanent.auth.service.A2aBeaconStore;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.A2aBeaconRpcService;
import org.apache.dubbo.config.annotation.DubboService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;

@DubboService
public class A2aBeaconRpcServiceImpl implements A2aBeaconRpcService {
    private final A2aBeaconStore store;
    private final AuthRagRpcProperties properties;
    public A2aBeaconRpcServiceImpl(A2aBeaconStore store, AuthRagRpcProperties properties) {
        this.store = store; this.properties = properties;
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
        if (properties.getResponseKeyId() == null || properties.getResponseSecret() == null
                || properties.getResponseSecret().getBytes(StandardCharsets.UTF_8).length < 32)
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        RagAuthEnvelope response = RagRequestSigner.sign(new RagAuthEnvelope(1, properties.getResponseKeyId(),
                authentication.callerService(), "auth-service", RagOperation.RESOLVE_PRINCIPAL_RESPONSE,
                authentication.requestId(), RagCanonicalHash.principalHash(principal), RagActorMode.USER,
                principal.userId().toString(), now, Math.min(authentication.expiresAtEpochSecond(), principal.expiresAtEpochSecond()),
                RagCanonicalHash.credentialDigest(credentials), null), properties.getResponseSecret().getBytes(StandardCharsets.UTF_8));
        return new AuthRagPrincipal(principal.userId(), principal.roleCode(), true, true, principal.expiresAtEpochSecond(), response);
    }
    private void verify(A2aBeaconBinding binding, RagCredentialContext credentials, RagAuthEnvelope auth, RagOperation operation) {
        if (binding == null || auth == null) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        var trusted = properties.getTrustedKeys().get(auth.keyId());
        if (trusted == null || trusted.getSecret() == null || trusted.getSecret().getBytes(StandardCharsets.UTF_8).length < 32)
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        RagRequestSigner.verify(auth, RagCanonicalHash.requestHash(binding), "auth-service", operation,
                auth.requestId(), credentials, new RagSigningKey(auth.keyId(), trusted.getCallerService(), null,
                Set.of(operation), trusted.getSecret().getBytes(StandardCharsets.UTF_8)), Instant.now());
    }
}
