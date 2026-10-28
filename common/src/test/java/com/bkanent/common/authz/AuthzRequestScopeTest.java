package com.bkanent.common.authz;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.bkanent.common.authz.AuthzTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthzRequestScopeTest {
    private static final String TOKEN = "request-user-access-token";
    private static final byte[] BODY = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final List<String> BROKER_PERMISSIONS = List.of("customer.read", "customer.update.own");

    @Test
    void exactIngressProofOpensLocalScopeAndBindsMappedBrokerToResource() throws Exception {
        var key = keyPair();
        var admission = admission(key, false, BROKER_PERMISSIONS, Map.of("BROKER", 41L));
        assertFalse(AuthzRequestScope.present());
        try (var scope = AuthzRequestScope.verifyAndOpen(admission.response(), admission.binding(), TOKEN,
                "customer-service", "POST", "/customers", null, BODY, Map.of(KID, key.getPublic()), Instant.ofEpochSecond(NOW))) {
            assertTrue(AuthzRequestScope.present());
            assertTrue(admission.response().snapshot().permissions().isEmpty());
            assertEquals(123, AuthzRequestScope.actorId());
            assertEquals(41L, AuthzRequestScope.requireBrokerIdentity());
            AuthzRequestScope.requireOperation("customer.read");
            AuthzRequestScope.requireBrokerOwnership(41L, "customer.read", "customer.read.all");
            assertThrows(AuthzException.class, () -> AuthzRequestScope.requireBrokerOwnership(
                    42L, "customer.read", "customer.read.all"));
            assertThrows(AuthzException.class, () -> AuthzRequestScope.requireOperation("customer.delete.own"));
        }
        assertFalse(AuthzRequestScope.present());
    }

    @Test
    void exactBodyAndExplicitBusinessWideOperationAreRequired() throws Exception {
        var key = keyPair();
        var broker = admission(key, false, BROKER_PERMISSIONS, Map.of("BROKER", 41L));
        assertThrows(AuthzException.class, () -> AuthzRequestScope.verifyAndOpen(broker.response(), broker.binding(), TOKEN,
                "customer-service", "POST", "/customers", null, "tampered".getBytes(), Map.of(KID, key.getPublic()),
                Instant.ofEpochSecond(NOW)));
        var admin = admission(key, true, List.of("customer.read.all"), Map.of());
        try (var ignored = AuthzRequestScope.verifyAndOpen(admin.response(), admin.binding(), TOKEN,
                "customer-service", "POST", "/customers", null, BODY, Map.of(KID, key.getPublic()), Instant.ofEpochSecond(NOW))) {
            AuthzRequestScope.requireBrokerOwnership(9001L, "customer.read", "customer.read.all");
            assertThrows(AuthzException.class, () -> AuthzRequestScope.requireBrokerIdentity());
        }
    }

    private static Admission admission(KeyPair key, boolean admin, List<String> permissions,
                                       Map<String, Long> identities) {
        var binding = new RequestBinding("gateway", "customer-service", "POST", "/customers", "/customers",
                List.of(), "request-1", null, null, AuthzCanonicalHash.bodyHash(BODY), HASH, NONCE,
                NOW, NOW + 60);
        String requestHash = AuthzCanonicalHash.resolveRequestHash(TOKEN, binding);
        var snapshotPermissions = AuthzOperationPolicy.requiredPermissions(permissions);
        var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("123", 1, 1, admin, snapshotPermissions, null,
                identities, AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        var context = AuthzSignature.sign(new AuthorizationContext("123", 1, 0,
                AuthzCanonicalHash.snapshotHash(snapshot), AuthzOperationPolicy.contextOperations(permissions),
                "customer-service", binding.requestId(),
                null, null, HASH, requestHash, binding.nonce(), NOW, NOW + 60, NOW + 300, identities,
                AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("123", 1, 0, AuthorizationStatus.ACTIVE,
                AuthzCanonicalHash.snapshotHash(snapshot), NOW, NOW + 30, NONCE,
                AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        return new Admission(new AuthzResponse(snapshot, context, freshness), binding);
    }

    private record Admission(AuthzResponse response, RequestBinding binding) { }
}
