package com.bkanent.auth;

import com.bkanent.auth.config.AuthA2aIdentityProperties;
import com.bkanent.auth.rpc.A2aBeaconRpcServiceImpl;
import com.bkanent.auth.service.A2aBeaconStore;
import com.bkanent.common.rag.*;
import com.bkanent.common.security.ServiceIdentityCrypto;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aBeaconRpcServiceImplTest {
    private static final byte[] LEGACY_SECRET = "legacy-a2a-test-key-material-at-least-32".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void issuesWithSourceIdentityAndResolvesWithAudienceIdentityWithoutDowngrade(@TempDir Path tempDir) throws Exception {
        var agent = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var interview = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var authIdentity = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var authPrivateKeyFile = writePrivateKey(tempDir.resolve("auth-identity.pem"), authIdentity);
        var store = mock(A2aBeaconStore.class);

        var agentTrusted = trusted("agent-service", agent, Set.of(RagOperation.ISSUE_BEACON));
        var interviewTrusted = trusted("interview-service", interview, Set.of(RagOperation.RESOLVE_BEACON));
        var a2aProperties = new AuthA2aIdentityProperties();
        a2aProperties.setTrustedIdentities(Map.of("agent-service-current", agentTrusted,
                "interview-service-current", interviewTrusted));
        a2aProperties.setResponseKeyId("auth-service-current");
        a2aProperties.setResponsePrivateKeyFile(authPrivateKeyFile.toString());

        var metrics = new SimpleMeterRegistry();
        var service = new A2aBeaconRpcServiceImpl(store, a2aProperties, metrics);
        assertThat(metrics.find("service.identity.signature.verified")
                .tags("protocol", "a2a-auth", "algorithm", RagAuthEnvelope.HMAC_SHA256).counter()).isNull();
        assertThat(counter(metrics, ServiceIdentityCrypto.ED25519)).isZero();

        long now = Instant.now().getEpochSecond();
        var binding = new A2aBeaconBinding(1, "request-id", "agent-service", "interview-service",
                "42", "POST /a2a", "a".repeat(64), "task-1", now + 60);
        var credentials = new RagCredentialContext("short-lived-test-token");
        when(store.issue(eq(binding), eq(credentials), eq(binding.credentialLeaseExpiresAtEpochSecond())))
                .thenReturn(new A2aBeacon("opaque-reference", now + 60));
        when(store.resolve(eq(binding), eq(credentials), eq("interview-service")))
                .thenReturn(new AuthRagPrincipal(42L, "ADMIN", true, true, now + 60));

        RagAuthEnvelope oldHmacRequest = RagRequestSigner.sign(envelope(binding, credentials,
                "agent-service-current", RagOperation.ISSUE_BEACON, "agent-service", now), LEGACY_SECRET);
        assertThatThrownBy(() -> service.issue(binding, credentials, oldHmacRequest)).isInstanceOf(RagException.class);

        RagAuthEnvelope issue = A2aAuthSignature.sign(envelope(binding, credentials,
                "agent-service-current", RagOperation.ISSUE_BEACON, "agent-service", now), agent.getPrivate());
        service.issue(binding, credentials, issue);

        RagAuthEnvelope resolve = A2aAuthSignature.sign(envelope(binding, credentials,
                "interview-service-current", RagOperation.RESOLVE_BEACON, "interview-service", now), interview.getPrivate());
        AuthRagPrincipal resolved = service.resolve(binding, credentials, resolve);
        assertThat(resolved.userId()).isEqualTo(42L);
        A2aAuthSignature.verify(resolved.attestation(), RagCanonicalHash.principalHash(resolved), "interview-service",
                RagOperation.RESOLVE_PRINCIPAL_RESPONSE, resolve.requestId(), "auth-service", "auth-service-current",
                credentials, Set.of(RagOperation.RESOLVE_PRINCIPAL_RESPONSE), authIdentity.getPublic(), Instant.now());
        verify(store).resolve(binding, credentials, "interview-service");

        assertThat(counter(metrics, ServiceIdentityCrypto.ED25519)).isEqualTo(2.0);
        assertThat(metrics.find("service.identity.signature.verified")
                .tags("protocol", "a2a-auth", "algorithm", RagAuthEnvelope.HMAC_SHA256).counter()).isNull();

        RagAuthEnvelope sourceCannotResolve = A2aAuthSignature.sign(envelope(binding, credentials,
                "agent-service-current", RagOperation.RESOLVE_BEACON, "agent-service", now), agent.getPrivate());
        assertThatThrownBy(() -> service.resolve(binding, credentials, sourceCannotResolve)).isInstanceOf(RagException.class);

        RagAuthEnvelope targetCannotIssue = A2aAuthSignature.sign(envelope(binding, credentials,
                "interview-service-current", RagOperation.ISSUE_BEACON, "agent-service", now), interview.getPrivate());
        assertThatThrownBy(() -> service.issue(binding, credentials, targetCannotIssue)).isInstanceOf(RagException.class);

        RagAuthEnvelope downgraded = new RagAuthEnvelope(issue.contractVersion(), issue.keyId(), issue.audience(),
                issue.callerService(), issue.operation(), issue.requestId(), issue.requestHash(), issue.actorMode(),
                issue.actorId(), issue.issuedAtEpochSecond(), issue.expiresAtEpochSecond(), issue.credentialDigest(),
                issue.signature(), RagAuthEnvelope.HMAC_SHA256);
        assertThatThrownBy(() -> service.issue(binding, credentials, downgraded)).isInstanceOf(RagException.class);
        assertThat(counter(metrics, ServiceIdentityCrypto.ED25519)).isEqualTo(2.0);
    }

    private static AuthA2aIdentityProperties.TrustedIdentity trusted(String service, KeyPair identity,
                                                                      Set<RagOperation> operations) {
        var trusted = new AuthA2aIdentityProperties.TrustedIdentity();
        trusted.setCallerService(service);
        trusted.setPublicKey(ServiceIdentityCrypto.encodePublicKey(identity.getPublic()));
        trusted.setAllowedOperations(operations);
        return trusted;
    }

    private static Path writePrivateKey(Path path, KeyPair identity) throws Exception {
        String encoded = Base64.getEncoder().encodeToString(identity.getPrivate().getEncoded());
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n");
        return path;
    }

    private static double counter(SimpleMeterRegistry metrics, String algorithm) {
        return metrics.get("service.identity.signature.verified")
                .tags("protocol", "a2a-auth", "algorithm", algorithm).counter().count();
    }

    private static RagAuthEnvelope envelope(A2aBeaconBinding binding, RagCredentialContext credentials,
                                            String keyId, RagOperation operation, String signer, long now) {
        return new RagAuthEnvelope(1, keyId, "auth-service", signer, operation,
                binding.requestId(), RagCanonicalHash.requestHash(binding), RagActorMode.SERVICE,
                signer, now, now + 45, RagCanonicalHash.credentialDigest(credentials), null);
    }
}
