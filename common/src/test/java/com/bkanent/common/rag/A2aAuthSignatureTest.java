package com.bkanent.common.rag;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aAuthSignatureTest {
    @Test
    void signsAndVerifiesEd25519V2AgainstTheA2aProtocolDomain() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var credentials = new RagCredentialContext("short-lived-access-token");
        long now = Instant.parse("2026-10-22T00:00:00Z").getEpochSecond();
        var unsigned = new RagAuthEnvelope(1, "marketing-service-current", "auth-service",
                "marketing-content-service", RagOperation.ISSUE_BEACON, "request-1", "a".repeat(64),
                RagActorMode.SERVICE, "marketing-content-service", now, now + 30,
                RagCanonicalHash.credentialDigest(credentials), null);
        RagAuthEnvelope signed = A2aAuthSignature.sign(unsigned, pair.getPrivate());

        assertThat(signed.contractVersion()).isEqualTo(A2aAuthSignature.ED25519_VERSION);
        assertThat(signed.algorithm()).isEqualTo("Ed25519");
        A2aAuthSignature.verify(signed, "a".repeat(64), "auth-service", RagOperation.ISSUE_BEACON,
                "request-1", "marketing-content-service", "marketing-service-current", credentials,
                Set.of(RagOperation.ISSUE_BEACON), pair.getPublic(), Instant.ofEpochSecond(now));

        assertThatThrownBy(() -> A2aAuthSignature.verify(signed, "a".repeat(64), "auth-service",
                RagOperation.ISSUE_BEACON, "request-1", "marketing-content-service", "marketing-service-current",
                credentials, Set.of(RagOperation.ISSUE_BEACON), KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair().getPublic(), Instant.ofEpochSecond(now)))
                .isInstanceOf(RagException.class);
        assertThatThrownBy(() -> A2aAuthSignature.verify(signed, "a".repeat(64), "auth-service",
                RagOperation.ISSUE_BEACON, "request-1", "marketing-content-service", "revoked-key-id",
                credentials, Set.of(RagOperation.ISSUE_BEACON), pair.getPublic(), Instant.ofEpochSecond(now)))
                .isInstanceOf(RagException.class);

        RagAuthEnvelope downgraded = new RagAuthEnvelope(signed.contractVersion(), signed.keyId(), signed.audience(),
                signed.callerService(), signed.operation(), signed.requestId(), signed.requestHash(), signed.actorMode(),
                signed.actorId(), signed.issuedAtEpochSecond(), signed.expiresAtEpochSecond(), signed.credentialDigest(),
                signed.signature(), RagAuthEnvelope.HMAC_SHA256);
        assertThatThrownBy(() -> A2aAuthSignature.verify(downgraded, "a".repeat(64), "auth-service",
                RagOperation.ISSUE_BEACON, "request-1", "marketing-content-service", "marketing-service-current",
                credentials, Set.of(RagOperation.ISSUE_BEACON), pair.getPublic(), Instant.ofEpochSecond(now)))
                .isInstanceOf(RagException.class);
    }
}
