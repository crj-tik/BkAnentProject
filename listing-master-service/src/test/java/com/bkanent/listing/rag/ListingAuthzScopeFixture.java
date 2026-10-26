package com.bkanent.listing.rag;

import com.bkanent.common.authz.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

final class ListingAuthzScopeFixture {
    static final long NOW = 1_800_000_000L;
    private static final String TOKEN = "listing-authz-test-token";
    private static final String KEY_ID = "auth-test-key";
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
    private static final String HASH = AuthzCanonicalHash.bodyHash(BODY);
    private static final String NONCE = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    private ListingAuthzScopeFixture() { }

    static AuthzRequestScope.Scope open(boolean businessAdmin, List<String> permissions,
                                         List<String> allowed, Map<String, Long> identities) {
        try {
            KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String path = "/listings/rag/search";
            var binding = new RequestBinding("gateway", "listing-master-service", "POST", path, path,
                    List.of(), "listing-request", null, null, HASH, HASH, NONCE, NOW, NOW + 60);
            var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("123", 1, 1, businessAdmin,
                    permissions, null, identities, AuthzContract.ALGORITHM, KEY_ID, null), key.getPrivate());
            String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
            String requestHash = AuthzCanonicalHash.resolveRequestHash(TOKEN, binding);
            var context = AuthzSignature.sign(new AuthorizationContext("123", 1, 0, permissionHash, allowed,
                    "listing-master-service", binding.requestId(), null, null, HASH, requestHash, NONCE,
                    NOW, NOW + 60, NOW + 300, identities, AuthzContract.ALGORITHM, KEY_ID, null), key.getPrivate());
            var freshness = AuthzSignature.sign(new AuthzFreshness("123", 1, 0, AuthorizationStatus.ACTIVE,
                    permissionHash, NOW, NOW + 30, NONCE, AuthzContract.ALGORITHM, KEY_ID, null), key.getPrivate());
            var response = new AuthzResponse(snapshot, context, freshness);
            return AuthzRequestScope.verifyAndOpen(response, binding, TOKEN, "listing-master-service", "POST",
                    path, null, BODY, Map.of(KEY_ID, key.getPublic()), Instant.ofEpochSecond(NOW));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
