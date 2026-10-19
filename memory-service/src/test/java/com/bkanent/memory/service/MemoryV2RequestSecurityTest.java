package com.bkanent.memory.service;

import com.bkanent.common.memory.MemoryCanonicalHash;
import com.bkanent.common.memory.MemoryContextKey;
import com.bkanent.common.memory.MemoryErrorCode;
import com.bkanent.common.memory.MemoryException;
import com.bkanent.common.memory.MemoryRequestEnvelope;
import com.bkanent.common.memory.MemoryRequestProof;
import com.bkanent.common.memory.MemoryRequestSigner;
import com.bkanent.common.memory.MemoryV2Contract;
import com.bkanent.common.memory.MemoryWorkingSetRequest;
import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.memory.config.MemoryV2SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
class MemoryV2RequestSecurityTest {
    private static final String CALLER = "marketing-service";
    private static final String KEY_ID = "marketing-memory-k1";
    private static final String SECRET = "local-test-secret-material-min-32-bytes";
    private static final String OWNER = "42";
    private static final MemoryContextKey CONTEXT = new MemoryContextKey(OWNER, "marketing", "marketing-agent", "conversation-1");

    private final StubAuth auth = new StubAuth();
    private final RecordingReplayGuard replay = new RecordingReplayGuard();
    private final MemoryV2SecurityProperties properties = new MemoryV2SecurityProperties();
    private MemoryV2RequestSecurity security;

    @BeforeEach
    void setUp() {
        MemoryV2SecurityProperties.TrustedCaller caller = new MemoryV2SecurityProperties.TrustedCaller();
        caller.setKeyId(KEY_ID);
        caller.setSigningSecret(SECRET);
        caller.setAllowedAgentsByDomain(Map.of("marketing", Set.of("marketing-agent")));
        properties.setTrustedCallers(Map.of(CALLER, caller));
        security = new MemoryV2RequestSecurity(properties, auth, replay);
        auth.allowed = userId -> userId == 42L;
    }

    @Test
    void acceptsSignedCurrentOwnerAndConsumesNonce() {
        MemoryWorkingSetRequest request = request(CONTEXT);
        MemoryRequestProof proof = proof(request, "nonce-abcdefghijklmnop");

        assertEquals(42L, security.authorize(request, proof, MemoryV2Contract.OPERATION_WORKING_SET));

        org.junit.jupiter.api.Assertions.assertEquals(42L, auth.lastUserId);
        org.junit.jupiter.api.Assertions.assertEquals("agent.chat.use", auth.lastPermission);
        org.junit.jupiter.api.Assertions.assertEquals(1, replay.calls);
        org.junit.jupiter.api.Assertions.assertEquals(KEY_ID, replay.lastKeyId);
        org.junit.jupiter.api.Assertions.assertEquals("nonce-abcdefghijklmnop", replay.lastNonce);
        org.junit.jupiter.api.Assertions.assertEquals(proof.expiresAtEpochSecond(), replay.lastExpiry);
    }

    @Test
    void rejectsCallerDomainAgentMismatchBeforeCheckingUser() {
        MemoryWorkingSetRequest request = request(new MemoryContextKey(OWNER, "interview", "interview-agent", "conversation-1"));
        MemoryRequestProof proof = proof(request, "nonce-abcdefghijklmnop");

        MemoryException forbidden = assertThrows(MemoryException.class,
                () -> security.authorize(request, proof, MemoryV2Contract.OPERATION_WORKING_SET));

        assertEquals(MemoryErrorCode.FORBIDDEN, forbidden.code());
        org.junit.jupiter.api.Assertions.assertEquals(0, auth.calls);
        org.junit.jupiter.api.Assertions.assertEquals(0, replay.calls);
    }

    @Test
    void rejectsTamperedOwnerAndOperation() {
        MemoryWorkingSetRequest request = request(CONTEXT);
        MemoryRequestProof proof = proof(request, "nonce-abcdefghijklmnop");
        MemoryWorkingSetRequest tampered = request(new MemoryContextKey("43", "marketing", "marketing-agent", "conversation-1"));

        MemoryException invalidProof = assertThrows(MemoryException.class,
                () -> security.authorize(tampered, proof, MemoryV2Contract.OPERATION_WORKING_SET));
        assertEquals(MemoryErrorCode.AUTHENTICATION_FAILED, invalidProof.code());

        MemoryException invalidOperation = assertThrows(MemoryException.class,
                () -> security.authorize(request, proof, MemoryV2Contract.OPERATION_GET_GRANT));
        assertEquals(MemoryErrorCode.AUTHENTICATION_FAILED, invalidOperation.code());
        org.junit.jupiter.api.Assertions.assertEquals(0, replay.calls);
    }

    @Test
    void inactiveOrUnauthorizedUserIsRejectedBeforeNonceConsumption() {
        auth.allowed = userId -> false;
        MemoryWorkingSetRequest request = request(CONTEXT);
        MemoryRequestProof proof = proof(request, "nonce-abcdefghijklmnop");

        MemoryException forbidden = assertThrows(MemoryException.class,
                () -> security.authorize(request, proof, MemoryV2Contract.OPERATION_WORKING_SET));

        assertEquals(MemoryErrorCode.FORBIDDEN, forbidden.code());
        org.junit.jupiter.api.Assertions.assertEquals(0, replay.calls);
    }

    @Test
    void authDependencyFailureFailsClosed() {
        auth.failure = new IllegalStateException("offline");
        MemoryWorkingSetRequest request = request(CONTEXT);
        MemoryRequestProof proof = proof(request, "nonce-abcdefghijklmnop");

        MemoryException unavailable = assertThrows(MemoryException.class,
                () -> security.authorize(request, proof, MemoryV2Contract.OPERATION_WORKING_SET));

        assertEquals(MemoryErrorCode.MEMORY_UNAVAILABLE, unavailable.code());
        org.junit.jupiter.api.Assertions.assertEquals(0, replay.calls);
    }

    private static MemoryWorkingSetRequest request(MemoryContextKey context) {
        MemoryRequestEnvelope placeholder = new MemoryRequestEnvelope(
                MemoryV2Contract.OPERATION_WORKING_SET, CALLER, MemoryV2Contract.MEMORY_AUDIENCE,
                "request-1", "0".repeat(64), MemoryV2Contract.VERSION);
        MemoryWorkingSetRequest draft = new MemoryWorkingSetRequest(placeholder, context);
        MemoryRequestEnvelope envelope = new MemoryRequestEnvelope(
                MemoryV2Contract.OPERATION_WORKING_SET, CALLER, MemoryV2Contract.MEMORY_AUDIENCE,
                "request-1", MemoryCanonicalHash.requestBodyHash(draft), MemoryV2Contract.VERSION);
        return new MemoryWorkingSetRequest(envelope, context);
    }

    private static MemoryRequestProof proof(MemoryWorkingSetRequest request, String nonce) {
        long now = Instant.now().getEpochSecond();
        return MemoryRequestSigner.sign(request, new AuthenticatedPrincipal(Long.parseLong(request.contextKey().ownerId())), KEY_ID, now, now + 45, nonce,
                SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class StubAuth implements AuthPermissionRpcService {
        private LongPredicate allowed = userId -> false;
        private RuntimeException failure;
        private long calls;
        private Long lastUserId;
        private String lastPermission;

        @Override public boolean validateToken(String token) { return false; }
        @Override public com.bkanent.common.rpc.AuthenticatedPrincipal resolvePrincipal(String token) { return null; }
        @Override public boolean hasPermission(Long userId, String permissionCode) {
            calls++;
            lastUserId = userId;
            lastPermission = permissionCode;
            if (failure != null) throw failure;
            return allowed.test(userId);
        }
    }

    private static final class RecordingReplayGuard extends MemoryV2ReplayGuard {
        private int calls;
        private String lastKeyId;
        private String lastNonce;
        private long lastExpiry;

        private RecordingReplayGuard() { super(null); }

        @Override public void consume(String keyId, String nonce, long expiresAtEpochSecond) {
            calls++;
            lastKeyId = keyId;
            lastNonce = nonce;
            lastExpiry = expiresAtEpochSecond;
        }
    }
}
