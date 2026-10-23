package com.bkanent.auth;

import com.bkanent.auth.config.AuthA2aIdentityProperties;
import com.bkanent.auth.config.AuthRagRpcProperties;
import com.bkanent.auth.rpc.A2aBeaconRpcServiceImpl;
import com.bkanent.auth.service.A2aBeaconStore;
import com.bkanent.common.rag.*;
import com.bkanent.common.security.ServiceIdentityCrypto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class A2aBeaconRpcServiceImplTest {
    private static final byte[] LEGACY_SECRET = "legacy-a2a-test-key-material-at-least-32".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void verifiesLegacyAndEd25519RequestsWithoutDowngradeAndCountsAcceptedAlgorithms() throws Exception {
        var identity = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var store = mock(A2aBeaconStore.class);
        var legacy = new AuthRagRpcProperties.TrustedKey();
        legacy.setCallerService("agent-service");
        legacy.setSecret(new String(LEGACY_SECRET, java.nio.charset.StandardCharsets.UTF_8));
        var ragProperties = new AuthRagRpcProperties();
        ragProperties.setTrustedKeys(Map.of("agent-a2a-legacy", legacy));

        var trusted = new AuthA2aIdentityProperties.TrustedIdentity();
        trusted.setCallerService("agent-service");
        trusted.setPublicKey(ServiceIdentityCrypto.encodePublicKey(identity.getPublic()));
        trusted.setAllowedOperations(Set.of(RagOperation.ISSUE_BEACON, RagOperation.RESOLVE_BEACON));
        var a2aProperties = new AuthA2aIdentityProperties();
        a2aProperties.setTrustedIdentities(Map.of("agent-service-current", trusted));
        var metrics = new SimpleMeterRegistry();
        var service = new A2aBeaconRpcServiceImpl(store, ragProperties, a2aProperties, metrics);

        long now = Instant.now().getEpochSecond();
        var binding = new A2aBeaconBinding(1, "request-id", "agent-service", "marketing-content-service",
                "42", "POST /a2a", "a".repeat(64), "task-1", now + 60);
        var credentials = new RagCredentialContext("short-lived-test-token");
        when(store.issue(eq(binding), eq(credentials), eq(binding.credentialLeaseExpiresAtEpochSecond())))
                .thenReturn(new A2aBeacon("opaque-reference", now + 60));

        RagAuthEnvelope legacyUnsigned = envelope(binding, credentials, "agent-a2a-legacy", now);
        RagAuthEnvelope legacyRequest = RagRequestSigner.sign(legacyUnsigned, LEGACY_SECRET);
        service.issue(binding, credentials, legacyRequest);

        RagAuthEnvelope edRequest = A2aAuthSignature.sign(envelope(binding, credentials,
                "agent-service-current", now), identity.getPrivate());
        service.issue(binding, credentials, edRequest);

        assertThat(metrics.get("service.identity.signature.verified")
                .tags("protocol", "a2a-auth", "algorithm", RagAuthEnvelope.HMAC_SHA256).counter().count()).isEqualTo(1.0);
        assertThat(metrics.get("service.identity.signature.verified")
                .tags("protocol", "a2a-auth", "algorithm", ServiceIdentityCrypto.ED25519).counter().count()).isEqualTo(1.0);

        RagAuthEnvelope downgraded = new RagAuthEnvelope(edRequest.contractVersion(), edRequest.keyId(), edRequest.audience(),
                edRequest.callerService(), edRequest.operation(), edRequest.requestId(), edRequest.requestHash(),
                edRequest.actorMode(), edRequest.actorId(), edRequest.issuedAtEpochSecond(), edRequest.expiresAtEpochSecond(),
                edRequest.credentialDigest(), edRequest.signature(), RagAuthEnvelope.HMAC_SHA256);
        assertThatThrownBy(() -> service.issue(binding, credentials, downgraded)).isInstanceOf(RagException.class);
        assertThat(metrics.get("service.identity.signature.verified")
                .tags("protocol", "a2a-auth", "algorithm", ServiceIdentityCrypto.ED25519).counter().count()).isEqualTo(1.0);
    }

    private static RagAuthEnvelope envelope(A2aBeaconBinding binding, RagCredentialContext credentials,
                                            String keyId, long now) {
        return new RagAuthEnvelope(1, keyId, "auth-service", binding.callerService(), RagOperation.ISSUE_BEACON,
                binding.requestId(), RagCanonicalHash.requestHash(binding), RagActorMode.SERVICE,
                binding.callerService(), now, now + 45, RagCanonicalHash.credentialDigest(credentials), null);
    }
}
