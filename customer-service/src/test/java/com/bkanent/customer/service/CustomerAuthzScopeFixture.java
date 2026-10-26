package com.bkanent.customer.service;

import com.bkanent.common.authz.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

final class CustomerAuthzScopeFixture {
    static final long NOW = 1_800_000_000L;
    private static final String TOKEN = "customer-authz-test-token";
    private static final String KEY_ID = "auth-test-key";
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
    private static final String HASH = AuthzCanonicalHash.bodyHash(BODY);
    private static final String NONCE = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    private CustomerAuthzScopeFixture() { }

    static AuthzRequestScope.Scope open(boolean businessAdmin, List<String> permissions,
                                         Map<String, Long> identities) {
        try {
            KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            String path = "/customers/1";
            var binding = new RequestBinding("gateway", "customer-service", "PUT", path, path,
                    List.of(), "customer-request", null, null, HASH, HASH, NONCE, NOW, NOW + 60);
            var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("123", 1, 1, businessAdmin,
                    permissions, null, identities, AuthzContract.ALGORITHM, KEY_ID, null), key.getPrivate());
            String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
            String requestHash = AuthzCanonicalHash.resolveRequestHash(TOKEN, binding);
            var context = AuthzSignature.sign(new AuthorizationContext("123", 1, 0, permissionHash, permissions,
                    "customer-service", binding.requestId(), null, null, HASH, requestHash, NONCE,
                    NOW, NOW + 60, NOW + 300, identities, AuthzContract.ALGORITHM, KEY_ID, null), key.getPrivate());
            var freshness = AuthzSignature.sign(new AuthzFreshness("123", 1, 0, AuthorizationStatus.ACTIVE,
                    permissionHash, NOW, NOW + 30, NONCE, AuthzContract.ALGORITHM, KEY_ID, null), key.getPrivate());
            var response = new AuthzResponse(snapshot, context, freshness);
            return AuthzRequestScope.verifyAndOpen(response, binding, TOKEN, "customer-service", "PUT",
                    path, null, BODY, Map.of(KEY_ID, key.getPublic()), Instant.ofEpochSecond(NOW));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
