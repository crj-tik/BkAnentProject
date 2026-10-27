package com.bkanent.businessreview.security;

import com.bkanent.businessreview.service.ReviewApplicationService;
import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.authz.*;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedStoreScopeAdapterTest {
    @Test
    void aSignedContextWithoutStoreIdentityFailsClosed() throws Exception {
        long now = Instant.now().getEpochSecond();
        String permission = ReviewApplicationService.READ;
        SignedRequest signed = signedRequest(now, Map.of(), List.of(permission));
        try (AuthzRequestScope.Scope ignored = verify(signed, Instant.now())) {
            assertThatThrownBy(() -> new VerifiedStoreScopeAdapter().requireStore(23L, permission))
                    .isInstanceOf(AuthzException.class)
                    .hasFieldOrPropertyWithValue("code", AuthzErrorCode.PERMISSION_DENIED);
        }
    }

    @Test
    void expiredSignedStoreScopeIsRejectedBeforeDomainUse() throws Exception {
        long issued = Instant.now().minusSeconds(600).getEpochSecond();
        SignedRequest signed = signedRequest(issued, Map.of(), List.of(ReviewApplicationService.READ));
        assertThatThrownBy(() -> verify(signed, Instant.now())).isInstanceOf(AuthzException.class);
    }

    @Test
    void noVerifiedContextCannotBeReplacedByRequestStoreId() {
        assertThatThrownBy(() -> new VerifiedStoreScopeAdapter().requireStore(23L,
                ReviewApplicationService.READ))
                .isInstanceOf(AuthzException.class);
    }

    private static SignedRequest signedRequest(long now, Map<String, Long> identities, List<String> permissions)
            throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String keyId = "auth-current";
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = AuthzCanonicalHash.bodyHash(new byte[0]);
        String token = "test-access-token";
        AuthorizationSnapshot unsignedSnapshot = new AuthorizationSnapshot("17", 3, 1, false,
                permissions, null, identities, AuthzContract.ALGORITHM, keyId, null);
        AuthorizationSnapshot snapshot = AuthzSignature.sign(unsignedSnapshot, keys.getPrivate());
        String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
        RequestBinding binding = new RequestBinding("gateway", "business-review-service", "GET",
                "/business-review/reviews", "/business-review/reviews",
                List.of(new RequestBinding.QueryParameter("storeId", "23")), "request-1", null, null,
                hash, hash, nonce, now, now + AuthzDeadlines.REQUEST_SECONDS);
        String requestHash = AuthzCanonicalHash.resolveRequestHash(token, binding);
        AuthorizationContext context = AuthzSignature.sign(new AuthorizationContext("17", 3, 0,
                permissionHash, permissions, "business-review-service", "request-1", null, null,
                hash, requestHash, nonce, now, now + AuthzDeadlines.CONTEXT_SECONDS,
                now + AuthzDeadlines.CONTEXT_SECONDS, identities, AuthzContract.ALGORITHM, keyId, null),
                keys.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness("17", 3, 0,
                AuthorizationStatus.ACTIVE, permissionHash, now, now + AuthzDeadlines.FRESHNESS_SECONDS,
                nonce, AuthzContract.ALGORITHM, keyId, null), keys.getPrivate());
        return new SignedRequest(new AuthzResponse(snapshot, context, freshness), binding, token,
                Map.of(keyId, keys.getPublic()));
    }

    private static AuthzRequestScope.Scope verify(SignedRequest signed, Instant now) {
        return AuthzRequestScope.verifyAndOpen(signed.response(), signed.binding(), signed.token(),
                "business-review-service", "GET", "/business-review/reviews", "storeId=23", new byte[0],
                signed.authorityKeys(), now);
    }

    private record SignedRequest(AuthzResponse response, RequestBinding binding, String token,
                                 Map<String, java.security.PublicKey> authorityKeys) { }
}
