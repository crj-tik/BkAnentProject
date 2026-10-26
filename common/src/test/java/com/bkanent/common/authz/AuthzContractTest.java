package com.bkanent.common.authz;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.bkanent.common.authz.AuthzTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthzContractTest {
    @Test
    void snapshotHashIsStableAcrossOrderingDuplicatesSigningRotationAndCacheRebuild() throws Exception {
        List<String> mutable = new ArrayList<>(List.of("memory.read", "agent.chat.use", "memory.read", "interview.read"));
        AuthorizationSnapshot rebuilt = new AuthorizationSnapshot("123", 7, 3, false, mutable, null,
                AuthzContract.ALGORITHM, "rotated-key", null);
        mutable.clear();
        assertEquals(PERMISSIONS, rebuilt.permissions());
        assertEquals(AuthzCanonicalHash.snapshotHash(snapshot()), AuthzCanonicalHash.snapshotHash(rebuilt));
        KeyPair key = keyPair();
        assertEquals(AuthzCanonicalHash.snapshotHash(rebuilt), AuthzCanonicalHash.snapshotHash(AuthzSignature.sign(rebuilt, key.getPrivate())));
        assertThrows(UnsupportedOperationException.class, () -> rebuilt.permissions().add("extra.permission"));
        assertThrows(UnsupportedOperationException.class, () -> context(snapshot()).allowedOperations().clear());
        assertThrows(UnsupportedOperationException.class, () -> binding().operations().clear());
        assertEquals(NOW + 300, AuthzDeadlines.contextExpiresAt(NOW + 100, NOW + 300, NOW + 300, null));
    }

    @Test
    void eachStableAuthorityFactChangesSnapshotHash() {
        String original = AuthzCanonicalHash.snapshotHash(snapshot());
        for (AuthorizationSnapshot changed : List.of(
                new AuthorizationSnapshot("124", 7, 3, false, PERMISSIONS, null, AuthzContract.ALGORITHM, KID, null),
                new AuthorizationSnapshot("123", 8, 3, false, PERMISSIONS, null, AuthzContract.ALGORITHM, KID, null),
                new AuthorizationSnapshot("123", 7, 4, false, PERMISSIONS, null, AuthzContract.ALGORITHM, KID, null),
                new AuthorizationSnapshot("123", 7, 3, true, PERMISSIONS, null, AuthzContract.ALGORITHM, KID, null),
                new AuthorizationSnapshot("123", 7, 3, false, List.of("memory.read"), null, AuthzContract.ALGORITHM, KID, null),
                new AuthorizationSnapshot("123", 7, 3, false, PERMISSIONS, NOW + 100, AuthzContract.ALGORITHM, KID, null))) {
            assertNotEquals(original, AuthzCanonicalHash.snapshotHash(changed));
        }
        AuthorizationSnapshot assigned = new AuthorizationSnapshot("123", 7, 3, false, PERMISSIONS, null,
                Map.of("BROKER", 901L), AuthzContract.ALGORITHM, KID, null);
        AuthorizationSnapshot reassigned = new AuthorizationSnapshot("123", 7, 3, false, PERMISSIONS, null,
                Map.of("BROKER", 902L), AuthzContract.ALGORITHM, KID, null);
        assertNotEquals(original, AuthzCanonicalHash.snapshotHash(assigned));
        assertNotEquals(AuthzCanonicalHash.snapshotHash(assigned), AuthzCanonicalHash.snapshotHash(reassigned));
        assertThrows(AuthzException.class, () -> new AuthorizationSnapshot("123", 7, 3, false,
                PERMISSIONS, null, Map.of("EMPLOYEE", 9L), AuthzContract.ALGORITHM, KID, null));
        assertThrows(UnsupportedOperationException.class, () -> assigned.businessIdentities().put("BROKER", 903L));
    }

    @Test
    void queryCanonicalizationPreservesDuplicatesAndBoundariesAndExactRequestTargets() {
        var a = new RequestBinding.QueryParameter("a", "1");
        var b = new RequestBinding.QueryParameter("b", "2");
        RequestBinding request = request(List.of(b, a, a));
        assertEquals(AuthzCanonicalHash.requestBindingHash(request), AuthzCanonicalHash.requestBindingHash(request(List.of(a, a, b))));
        assertNotEquals(AuthzCanonicalHash.requestBindingHash(request), AuthzCanonicalHash.requestBindingHash(request(List.of(a, b))));
        assertNotEquals(AuthzCanonicalHash.requestBindingHash(request(List.of(new RequestBinding.QueryParameter("ab", "c")))),
                AuthzCanonicalHash.requestBindingHash(request(List.of(new RequestBinding.QueryParameter("a", "bc")))));
        RequestBinding rewrite = new RequestBinding(request.callerService(), request.audience(), request.method(),
                request.originalPath(), "/different", request.query(), request.requestId(), request.runId(), request.sessionId(),
                request.bodyHash(), request.policyDigest(), request.nonce(), NOW, NOW + 60);
        assertNotEquals(AuthzCanonicalHash.requestBindingHash(request), AuthzCanonicalHash.requestBindingHash(rewrite));
        assertNotEquals(AuthzCanonicalHash.resolveRequestHash("access-a", request), AuthzCanonicalHash.resolveRequestHash("access-b", request));
        assertThrows(UnsupportedOperationException.class, () -> request.query().clear());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", AuthzCanonicalHash.bodyHash(new byte[0]));
        assertThrows(AuthzException.class, () -> AuthzCanonicalHash.bodyHash(new byte[AuthzContract.MAX_BODY_BYTES + 1]));
    }

    @Test
    void actorVersionHashNonceAndCatalogBoundsFailClosedWithoutEchoingInput() {
        for (String actor : List.of("0", "-1", "+1", "01", " 1", "9223372036854775808", "secret-actor")) {
            AuthzException error = assertThrows(AuthzException.class, () -> new AuthorizationSnapshot(actor, 1, 1, false,
                    List.of(), null, AuthzContract.ALGORITHM, KID, null));
            assertFalse(error.getMessage().contains(actor));
            assertNull(error.getCause());
        }
        assertThrows(AuthzException.class, () -> new AuthorizationSnapshot("123", 0, 1, false, List.of(), null, AuthzContract.ALGORITHM, KID, null));
        assertThrows(AuthzException.class, () -> new AuthorizationSnapshot("123", 1, 0, false, List.of(), null, AuthzContract.ALGORITHM, KID, null));
        assertThrows(AuthzException.class, () -> new AuthorizationSnapshot("123", 1, 1, false,
                java.util.Collections.nCopies(AuthzContract.MAX_PERMISSIONS + 1, "memory.read"), null, AuthzContract.ALGORITHM, KID, null));
        for (String permission : List.of("*", "memory.*", "MEMORY.read", "memory", "memory.read\n")) {
            assertThrows(AuthzException.class, () -> new AuthorizationSnapshot("123", 1, 1, false,
                    List.of(permission), null, AuthzContract.ALGORITHM, KID, null));
        }
        assertThrows(AuthzException.class, () -> new AuthzFreshness("123", 1, 0, AuthorizationStatus.ACTIVE,
                HASH.toUpperCase(), NOW, NOW + 30, NONCE, AuthzContract.ALGORITHM, KID, null));
        assertThrows(AuthzException.class, () -> new AuthzFreshness("123", 1, 0, AuthorizationStatus.ACTIVE,
                HASH, NOW, NOW + 30, "nonce", AuthzContract.ALGORITHM, KID, null));
        assertThrows(AuthzException.class, () -> new RequestBinding.QueryParameter("x", "\uD800"));
    }

    @Test
    void hardDeadlinesAreFixedAndTakeMinimumWithNoSlidingFreshness() {
        assertEquals(NOW + 300, AuthzDeadlines.entryRunHardDeadline(NOW, NOW + 900, null));
        assertEquals(NOW + 90, AuthzDeadlines.entryRunHardDeadline(NOW, NOW + 900, NOW + 90));
        assertEquals(NOW + 80, AuthzDeadlines.contextExpiresAt(NOW, NOW + 90, NOW + 100, NOW + 80));
        assertEquals(NOW + 60, AuthzDeadlines.requestExpiresAt(NOW, NOW + 300));
        assertEquals(NOW + 10, AuthzDeadlines.requestExpiresAt(NOW, NOW + 10));
        assertEquals(NOW + 30, AuthzDeadlines.freshnessNotAfter(NOW));
        assertEquals(AuthzErrorCode.EXPIRED, assertThrows(AuthzException.class,
                () -> AuthzDeadlines.contextExpiresAt(NOW + 300, NOW + 300, NOW + 300, null)).code());
        assertThrows(AuthzException.class, () -> AuthzDeadlines.entryRunHardDeadline(Long.MAX_VALUE, Long.MAX_VALUE, null));
        assertThrows(AuthzException.class, () -> new AuthzFreshness("123", 7, 0, AuthorizationStatus.ACTIVE,
                HASH, NOW, NOW + 31, NONCE, AuthzContract.ALGORITHM, KID, null));
        DelegationBinding base = binding();
        assertThrows(AuthzException.class, () -> new DelegationBinding(base.callerService(), base.audience(), "123",
                base.operations(), base.runId(), base.taskId(), base.requestId(), HASH, HASH, NONCE, NOW, NOW + 61));
        AuthorizationContext context = context(snapshot());
        assertThrows(AuthzException.class, () -> new AuthorizationContext("123", 7, 0, context.permissionHash(), PERMISSIONS,
                context.audience(), context.requestId(), context.runId(), context.sessionId(), HASH, HASH, NONCE,
                NOW, NOW + 301, NOW + 301, AuthzContract.ALGORITHM, KID, null));
    }

    @Test
    void signedRecordsRoundTripJavaAndJacksonAndRemainImmutableAndRedacted() throws Exception {
        KeyPair key = keyPair();
        AuthorizationSnapshot snapshot = AuthzSignature.sign(snapshot(), key.getPrivate());
        AuthorizationContext context = AuthzSignature.sign(context(snapshot), key.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(freshness(snapshot), key.getPrivate());
        AuthzResponse response = new AuthzResponse(snapshot, context, freshness);
        ObjectMapper mapper = new ObjectMapper();
        for (Object dto : List.of(snapshot, context, freshness, response, binding(), request(List.of()),
                AuthzSignature.sign(caller(binding(), context), key.getPrivate()))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(dto); }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                assertEquals(dto, in.readObject());
            }
            assertEquals(dto, mapper.readValue(mapper.writeValueAsBytes(dto), dto.getClass()));
            assertTrue(dto.toString().endsWith("[REDACTED]"));
            assertFalse(dto.toString().contains(NONCE));
        }
        AuthzResponse rebuilt = mapper.readValue(mapper.writeValueAsBytes(response), AuthzResponse.class);
        AuthzSignature.verifyResponse(rebuilt, context.audience(), context.requestId(), HASH, NONCE,
                Map.of(KID, key.getPublic()), java.time.Instant.ofEpochSecond(NOW));
        assertThrows(UnsupportedOperationException.class, () -> rebuilt.snapshot().permissions().clear());
        assertThrows(UnsupportedOperationException.class, () -> rebuilt.context().allowedOperations().clear());
    }
}
