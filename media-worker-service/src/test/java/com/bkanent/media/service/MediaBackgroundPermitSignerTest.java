package com.bkanent.media.service;

import com.bkanent.common.authz.*;
import com.bkanent.common.model.MediaGenerateTaskRequest;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.media.config.MediaAuthorizationProperties;
import com.bkanent.media.model.MediaBackgroundPermit;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class MediaBackgroundPermitSignerTest {
    @Test
    void permitBindsAuthContextOwnerListingAndExactTaskPayload() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var properties = new MediaAuthorizationProperties();
        properties.setEnabled(true);
        properties.setSigningSecret("m".repeat(40));
        properties.setAuthorityPublicKeys(Map.of("auth-test", ServiceIdentityCrypto.encodePublicKey(authority.getPublic())));
        long now = Instant.now().getEpochSecond();
        long expires = now + 120;
        long freshUntil = now + 25;
        String hash = "a".repeat(64);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        List<String> operations = List.of("listing.assets.bind.own", "media.generate.background",
                "media.task.submit.own");
        var context = AuthzSignature.sign(new AuthorizationContext("73", 1, 0, hash, operations,
                "agent-service", "entry-73", "run-73", "session-73", hash, hash, nonce,
                now, expires, expires, Map.of("BROKER", 901L), AuthzContract.ALGORITHM, "auth-test", null),
                authority.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("73", 1, 0, AuthorizationStatus.ACTIVE,
                hash, now, freshUntil, nonce, AuthzContract.ALGORITHM, "auth-test", null), authority.getPrivate());
        var verified = new com.bkanent.common.a2a.VerifiedDomainAuthorization.VerifiedContext(
                "73", context, freshness, freshUntil);
        var request = request("阳光花园客厅", "media-worker-service");
        var signer = new MediaBackgroundPermitSigner(properties);
        MediaBackgroundPermit permit = signer.create("task-73", request, verified);

        assertThatCode(() -> signer.verify(permit, "task-73", request)).doesNotThrowAnyException();
        assertThatThrownBy(() -> signer.verify(permit, "task-73", request("另一个房源", "media-worker-service")))
                .isInstanceOf(AuthzException.class);
        assertThatThrownBy(() -> signer.verify(permit, "task-74", request))
                .isInstanceOf(AuthzException.class);
    }

    @Test
    void backgroundTaskWithoutExactPermissionCannotReceivePermit() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var properties = new MediaAuthorizationProperties();
        properties.setEnabled(true);
        properties.setSigningSecret("m".repeat(40));
        properties.setAuthorityPublicKeys(Map.of("auth-test", ServiceIdentityCrypto.encodePublicKey(authority.getPublic())));
        long now = Instant.now().getEpochSecond();
        long expires = now + 30;
        String hash = "b".repeat(64);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var context = AuthzSignature.sign(new AuthorizationContext("73", 1, 0, hash,
                List.of("listing.assets.bind.own", "media.task.submit.own"), "agent-service", "entry-73",
                "run-73", "session-73", hash, hash, nonce, now, expires, expires,
                Map.of("BROKER", 901L), AuthzContract.ALGORITHM, "auth-test", null), authority.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("73", 1, 0, AuthorizationStatus.ACTIVE,
                hash, now, now + 20, nonce, AuthzContract.ALGORITHM, "auth-test", null), authority.getPrivate());
        var verified = new com.bkanent.common.a2a.VerifiedDomainAuthorization.VerifiedContext(
                "73", context, freshness, now + 20);

        assertThatThrownBy(() -> new MediaBackgroundPermitSigner(properties)
                .create("task-73", request("prompt", "media-worker-service"), verified))
                .isInstanceOf(AuthzException.class);
    }

    private MediaGenerateTaskRequest request(String prompt, String caller) {
        return new MediaGenerateTaskRequest(101L, null, caller, "LISTING_IMAGE", prompt,
                List.of("living-room"), 1);
    }
}
