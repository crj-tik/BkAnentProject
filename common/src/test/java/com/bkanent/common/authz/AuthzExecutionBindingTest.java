package com.bkanent.common.authz;

import com.bkanent.common.serialize.Fastjson2ConstructorSerialization;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.bkanent.common.authz.AuthzTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthzExecutionBindingTest {
    @Test
    void dtoNormalizesImmutableScopeAndRoundTripsConstructorSerialization() throws Exception {
        var mutable = new ArrayList<>(List.of("interview.read", "agent.chat.use", "interview.read"));
        var binding = binding(mutable);
        mutable.clear();
        assertEquals(List.of("agent.chat.use", "interview.read"), binding.operations());
        assertThrows(UnsupportedOperationException.class, () -> binding.operations().clear());
        var serialization = new Fastjson2ConstructorSerialization();
        var bytes = new ByteArrayOutputStream();
        var output = serialization.serialize(null, bytes);
        output.writeObject(binding);
        output.flushBuffer();
        var input = serialization.deserialize(null, new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(binding, input.readObject(ExecutionBinding.class));
        assertEquals("ExecutionBinding[REDACTED]", binding.toString());
    }

    @Test
    void dtoRequiresConcreteIdentityCanonicalHashesNonceEd25519AndAtMostSixtySeconds() {
        assertThrows(AuthzException.class, () -> new ExecutionBinding("agent-service", "123", null, "session-1",
                List.of("agent.chat.use"), HASH, HASH, HASH, "bind-1", nonce(2), NOW, NOW + 60, AuthzContract.ALGORITHM));
        assertThrows(AuthzException.class, () -> new ExecutionBinding("agent-service", "123", "run-1", null,
                List.of("agent.chat.use"), HASH, HASH, HASH, "bind-1", nonce(2), NOW, NOW + 60, AuthzContract.ALGORITHM));
        assertThrows(AuthzException.class, () -> new ExecutionBinding("agent-service", "123", "run-1", "session-1",
                List.of("agent.chat.use"), HASH, HASH, HASH, "bind-1", nonce(2), NOW, NOW + 61, AuthzContract.ALGORITHM));
        assertThrows(AuthzException.class, () -> new ExecutionBinding("agent-service", "123", "run-1", "session-1",
                List.of("agent.chat.use"), HASH, HASH, HASH, "bind-1", nonce(2), NOW, NOW + 60, "HMAC"));
        assertThrows(AuthzException.class, () -> new ExecutionBinding("agent-service", "123", "run-1", "session-1",
                List.of("agent.chat.use"), "A".repeat(64), HASH, HASH, "bind-1", nonce(2), NOW, NOW + 60, AuthzContract.ALGORITHM));
    }

    @Test
    void requestHashCoversCompleteSignedIngressAndBindingWhileTupleExcludesRetryMetadata() throws Exception {
        var key = keyPair();
        var ingress = ingress(key);
        var binding = binding(List.of("agent.chat.use"));
        var retry = new ExecutionBinding(binding.callerService(), binding.actorId(), binding.runId(), binding.sessionId(),
                binding.operations(), binding.ingressRequestHash(), binding.executionHash(), binding.policyDigest(),
                "retry-2", nonce(3), NOW + 1, NOW + 61, binding.algorithm());
        String requestHash = AuthzCanonicalHash.executionBindingRequestHash(ingress, binding);
        assertNotEquals(requestHash, AuthzCanonicalHash.executionBindingRequestHash(ingress, retry));
        assertEquals(AuthzCanonicalHash.executionBindingTupleHash(ingress, binding),
                AuthzCanonicalHash.executionBindingTupleHash(ingress, retry));
        var differentKey = keyPair();
        for (AuthzResponse changed : List.of(
                new AuthzResponse(AuthzSignature.sign(ingress.snapshot(), differentKey.getPrivate()), ingress.context(), ingress.freshness()),
                new AuthzResponse(ingress.snapshot(), AuthzSignature.sign(ingress.context(), differentKey.getPrivate()), ingress.freshness()),
                new AuthzResponse(ingress.snapshot(), ingress.context(), AuthzSignature.sign(ingress.freshness(), differentKey.getPrivate())))) {
            assertNotEquals(requestHash, AuthzCanonicalHash.executionBindingRequestHash(changed, binding));
        }
        var c = ingress.context();
        var request = new RequestBinding("agent-service", "agent-service", "POST", "/bind", "/bind", List.of(),
                binding.requestId(), c.runId(), c.sessionId(), HASH, HASH, binding.nonce(), NOW, NOW + 60);
        assertNotEquals(requestHash, AuthzCanonicalHash.refreshRequestHash("synthetic-access", c, request));
        assertEquals(AuthzCanonicalHash.executionOperationsHash(List.of("interview.read", "agent.chat.use", "interview.read")),
                AuthzCanonicalHash.executionOperationsHash(List.of("agent.chat.use", "interview.read")));
    }

    @Test
    void consumerVerifiesRunTransitionAndRejectsWrongResponseRunCorrelationScopeAndDeadline() throws Exception {
        var key = keyPair();
        var ingress = ingress(key);
        var binding = binding(List.of("agent.chat.use"));
        var response = bound(ingress, binding, key, "run-1", List.of("agent.chat.use"), NOW + 300);
        var keys = Map.of(KID, key.getPublic());
        AuthzSignature.verifyExecutionBindingResponse(ingress, binding, response, keys, Instant.ofEpochSecond(NOW));
        var mismatches = List.of(bound(ingress, binding, key, "other-run", binding.operations(), NOW + 300),
                bound(ingress, binding, key, "run-1", PERMISSIONS, NOW + 300));
        for (AuthzResponse wrong : mismatches) {
            assertThrows(AuthzException.class, () -> AuthzSignature.verifyExecutionBindingResponse(
                    ingress, binding, wrong, keys, Instant.ofEpochSecond(NOW)));
        }
        var retry = new ExecutionBinding("agent-service", "123", "run-1", "session-1", binding.operations(),
                HASH, HASH, HASH, "retry-2", nonce(3), NOW, NOW + 60, AuthzContract.ALGORITHM);
        assertThrows(AuthzException.class, () -> AuthzSignature.verifyExecutionBindingResponse(
                ingress, retry, response, keys, Instant.ofEpochSecond(NOW)));
        // A context can be shorter than its fixed hard limit, but binding cannot restore that earlier expiry.
        var c = ingress.context();
        var shortIngressContext = AuthzSignature.sign(new AuthorizationContext(c.actorId(), c.permissionRevision(), c.credentialEpoch(),
                c.permissionHash(), c.allowedOperations(), c.audience(), c.requestId(), null, null, c.policyDigest(), HASH,
                c.nonce(), NOW, NOW + 80, NOW + 300, c.algorithm(), c.keyId(), null), key.getPrivate());
        var shortIngress = new AuthzResponse(ingress.snapshot(), shortIngressContext, ingress.freshness());
        var tooLong = bound(shortIngress, binding, key, "run-1", binding.operations(), NOW + 100);
        assertThrows(AuthzException.class, () -> AuthzSignature.verifyExecutionBindingResponse(
                shortIngress, binding, tooLong, keys, Instant.ofEpochSecond(NOW)));
    }

    private static ExecutionBinding binding(List<String> operations) {
        return new ExecutionBinding("agent-service", "123", "run-1", "session-1", operations, HASH, HASH, HASH,
                "binding-1", nonce(2), NOW, NOW + 60, AuthzContract.ALGORITHM);
    }

    private static AuthzResponse ingress(KeyPair key) {
        var snapshot = AuthzSignature.sign(snapshot(), key.getPrivate());
        var c = context(snapshot);
        var context = AuthzSignature.sign(new AuthorizationContext(c.actorId(), c.permissionRevision(), c.credentialEpoch(),
                c.permissionHash(), c.allowedOperations(), c.audience(), c.requestId(), null, null, c.policyDigest(), c.requestHash(),
                c.nonce(), c.issuedAtEpochSecond(), c.expiresAtEpochSecond(), c.runHardDeadlineEpochSecond(), c.algorithm(),
                c.keyId(), null), key.getPrivate());
        return new AuthzResponse(snapshot, context, AuthzSignature.sign(freshness(snapshot), key.getPrivate()));
    }

    private static AuthzResponse bound(AuthzResponse ingress, ExecutionBinding binding, KeyPair key,
                                       String run, List<String> scope, long expiry) {
        var c = ingress.context();
        var context = AuthzSignature.sign(new AuthorizationContext(c.actorId(), c.permissionRevision(), c.credentialEpoch(),
                c.permissionHash(), scope, c.audience(), binding.requestId(), run, binding.sessionId(), c.policyDigest(),
                AuthzCanonicalHash.executionBindingRequestHash(ingress, binding), binding.nonce(), NOW, expiry,
                c.runHardDeadlineEpochSecond(), c.algorithm(), c.keyId(), null), key.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness(c.actorId(), c.permissionRevision(), c.credentialEpoch(),
                AuthorizationStatus.ACTIVE, c.permissionHash(), NOW, NOW + 30, binding.nonce(), c.algorithm(), c.keyId(), null), key.getPrivate());
        return new AuthzResponse(ingress.snapshot(), context, freshness);
    }

    private static String nonce(int id) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
