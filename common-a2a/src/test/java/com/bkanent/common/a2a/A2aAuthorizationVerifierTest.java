package com.bkanent.common.a2a;

import com.bkanent.common.authz.*;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.security.ServiceIdentityCrypto;
import io.a2a.util.Utils;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.ServerRequest;
import reactor.core.publisher.Flux;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class A2aAuthorizationVerifierTest {
    private static final String AUTH_KEY = "auth-k1";
    private static final String AGENT_KEY = "agent-k1";
    private static final String OPERATION = "agent.workflow.invoke.listing";

    @Test
    void verifiesBoundContextAndEachRequestLocallyWithoutAuthAndUsesSignedActor() throws Exception {
        long now = Instant.now().getEpochSecond();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC);
        KeyPair authority = keyPair();
        KeyPair agent = keyPair();
        var properties = properties(authority, agent);
        var authentication = mock(A2aBeaconAuthentication.class);
        when(authentication.service()).thenReturn("listing-master-service");
        var verifier = verifier(properties, clock);
        var guard = new A2aAuthenticatedRequestGuard(authentication, verifier);

        String send = sendBody("task-local-proof", "999");
        var signedSend = signedRequest(send, "7", now, authority, agent);
        var sendHeaders = headers(signedSend);
        Object result = guard.handle(send, sendHeaders, A2aCredentialContext::currentActorId);
        assertThat(result).isEqualTo("7");

        String get = getBody("task-local-proof", "rpc-get");
        var signedGet = signedRequest(get, "7", now, authority, agent);
        AtomicInteger events = new AtomicInteger();
        Object stream = guard.handle(get, headers(signedGet), () -> Flux.just("one", "two"));
        Object last = ((Flux<?>) stream).doOnNext(ignored -> events.incrementAndGet()).blockLast();
        assertThat(last).isEqualTo("two");
        assertThat(events).hasValue(2);

        verifyNoInteractions(authentication);
    }

    @Test
    void rejectsTamperedBodyAndReplayedNonceBeforeOfficialHandler() throws Exception {
        long now = Instant.now().getEpochSecond();
        KeyPair authority = keyPair();
        KeyPair agent = keyPair();
        var properties = properties(authority, agent);
        var verifier = verifier(properties, Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC));
        var guard = new A2aAuthenticatedRequestGuard(mock(A2aBeaconAuthentication.class), verifier);
        String body = sendBody("task-replay-proof", "7");
        var signed = signedRequest(body, "7", now, authority, agent);
        var called = new AtomicInteger();
        guard.handle(body, headers(signed), () -> { called.incrementAndGet(); return "accepted"; });

        assertThatThrownBy(() -> guard.handle(body + " ", headers(signed), () -> {
            called.incrementAndGet(); return "must not run";
        })).isInstanceOf(RagException.class);
        assertThatThrownBy(() -> guard.handle(body, headers(signed), () -> {
            called.incrementAndGet(); return "replay";
        })).isInstanceOf(RagException.class);
        assertThat(called).hasValue(1);
    }

    @Test
    void signedRequestIsBoundToTheOriginalTaskOwner() throws Exception {
        long now = Instant.now().getEpochSecond();
        KeyPair authority = keyPair();
        KeyPair agent = keyPair();
        var properties = properties(authority, agent);
        var verifier = verifier(properties, Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC));
        var guard = new A2aAuthenticatedRequestGuard(mock(A2aBeaconAuthentication.class), verifier);
        String body = sendBody("task-owner-proof", "7");
        guard.handle(body, headers(signedRequest(body, "7", now, authority, agent)), () -> "accepted");

        var foreign = signedRequest(body, "8", now, authority, agent);
        var called = new AtomicInteger();
        assertThatThrownBy(() -> guard.handle(body, headers(foreign), () -> {
            called.incrementAndGet(); return "must not run";
        })).isInstanceOf(RagException.class);
        assertThat(called).hasValue(0);
    }

    @Test
    void sharedStoreRejectsCrossInstanceReplayAndKeepsReservationUntilBothBoundariesFinish() throws Exception {
        long now = Instant.now().getEpochSecond();
        KeyPair authority = keyPair();
        KeyPair agent = keyPair();
        var store = new TestTaskAuthorizationStore();
        Clock clock = Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC);
        var first = new A2aAuthorizationVerifier("listing-master-service", properties(authority, agent), clock, store);
        var second = new A2aAuthorizationVerifier("listing-master-service", properties(authority, agent), clock, store);

        String body = sendBody("task-shared-state", "999");
        var firstEnvelope = signedRequest(body, "7", now, authority, agent);
        String encoded = AuthzWireCodec.encodeDelegatedAuthorizationRequest(firstEnvelope);
        var verified = first.verify(encoded, body);
        first.bindOrVerifyTaskOwner(verified);
        first.reserveTask(verified);
        var lease = A2aCredentialContext.Lease.versioned(firstEnvelope, "message/send",
                () -> first.releaseTaskReservation(verified));
        try (var ignored = A2aCredentialContext.open(lease)) {
            A2aCredentialContext.reserveTask(verified.facts().taskId());
            assertThat(A2aCredentialContext.markDispatched(lease)).isTrue();
        }
        A2aCredentialContext.releaseUndispatchedTask(verified.facts().taskId(), lease);

        assertThatThrownBy(() -> second.verify(encoded, body)).isInstanceOf(RagException.class);
        var inFlightRequest = signedRequest(body.replace("rpc-task-shared-state", "rpc-next-1"), "7", now, authority, agent);
        var inFlight = second.verify(AuthzWireCodec.encodeDelegatedAuthorizationRequest(inFlightRequest),
                body.replace("rpc-task-shared-state", "rpc-next-1"));
        second.bindOrVerifyTaskOwner(inFlight);
        assertThatThrownBy(() -> second.reserveTask(inFlight)).isInstanceOf(RagException.class);

        A2aCredentialContext.releaseDispatchedTask(lease);
        String nextBody = body.replace("rpc-task-shared-state", "rpc-next-2");
        var next = signedRequest(nextBody, "7", now, authority, agent);
        var nextVerified = second.verify(AuthzWireCodec.encodeDelegatedAuthorizationRequest(next), nextBody);
        second.bindOrVerifyTaskOwner(nextVerified);
        assertThatCode(() -> second.reserveTask(nextVerified)).doesNotThrowAnyException();
        second.releaseTaskReservation(nextVerified);
    }

    @Test
    void rejectsCapabilityOutsideTheAuthSignedParentScopeBeforeHandler() throws Exception {
        long now = Instant.now().getEpochSecond();
        KeyPair authority = keyPair();
        KeyPair agent = keyPair();
        var verifier = verifier(properties(authority, agent), Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC));
        var guard = new A2aAuthenticatedRequestGuard(mock(A2aBeaconAuthentication.class), verifier);
        String body = sendBody("task-capability-proof", "7");
        var unauthorized = signedRequest(body, "7", now, authority, agent,
                List.of("agent.workflow.invoke.contract"));
        var called = new AtomicInteger();

        assertThatThrownBy(() -> guard.handle(body, headers(unauthorized), () -> {
            called.incrementAndGet(); return "must not run";
        })).isInstanceOf(RagException.class);
        assertThat(called).hasValue(0);
    }

    private static A2aAuthorizationProperties properties(KeyPair authority, KeyPair agent) {
        var properties = new A2aAuthorizationProperties();
        properties.setEnabled(true);
        properties.setAuthorityPublicKeys(Map.of(AUTH_KEY, ServiceIdentityCrypto.encodePublicKey(authority.getPublic())));
        properties.setTrustedCallerPublicKeys(Map.of(AGENT_KEY, ServiceIdentityCrypto.encodePublicKey(agent.getPublic())));
        return properties;
    }

    private static A2aAuthorizationVerifier verifier(A2aAuthorizationProperties properties, Clock clock) {
        return new A2aAuthorizationVerifier("listing-master-service", properties, clock,
                new TestTaskAuthorizationStore());
    }

    private static final class TestTaskAuthorizationStore implements A2aTaskAuthorizationStore {
        private final java.util.Set<String> nonces = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Map<String, TestOwner> owners = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, TestReservation> reservations = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public boolean consumeNonce(String nonce, long expiresAtEpochSecond, long nowEpochSecond) {
            return nonces.add(nonce);
        }

        @Override public boolean bindOrVerifyTaskOwner(String taskId, String actorId, String callerService,
                                                        String a2aContextId, String runId, String sessionId,
                                                        boolean sending) {
            TestOwner candidate = new TestOwner(actorId, callerService, a2aContextId, runId, sessionId);
            if (sending) owners.putIfAbsent(taskId, candidate);
            TestOwner owner = owners.get(taskId);
            return owner != null && owner.actorId.equals(actorId) && owner.callerService.equals(callerService)
                    && (!sending || java.util.Objects.equals(owner.contextId, a2aContextId))
                    && owner.runId.equals(runId) && owner.sessionId.equals(sessionId);
        }

        @Override public boolean reserveTask(String taskId, String actorId, String nonce,
                                             long expiresAtEpochSecond, long nowEpochSecond) {
            java.util.concurrent.atomic.AtomicBoolean reserved = new java.util.concurrent.atomic.AtomicBoolean();
            reservations.compute(taskId, (ignored, existing) -> {
                if (existing == null || existing.expiresAt <= nowEpochSecond) {
                    reserved.set(true);
                    return new TestReservation(actorId, nonce, expiresAtEpochSecond);
                }
                return existing;
            });
            return reserved.get();
        }

        @Override public void releaseTaskReservation(String taskId, String nonce) {
            reservations.computeIfPresent(taskId, (ignored, existing) -> existing.nonce.equals(nonce) ? null : existing);
        }
    }

    private record TestOwner(String actorId, String callerService, String contextId, String runId, String sessionId) { }
    private record TestReservation(String actorId, String nonce, long expiresAt) { }

    private static ServerRequest.Headers headers(DelegatedAuthorizationRequest request) throws Exception {
        String encoded = AuthzWireCodec.encodeDelegatedAuthorizationRequest(request);
        var headers = mock(ServerRequest.Headers.class);
        when(headers.header(BeaconA2aHttpClient.AUTHORIZATION_HEADER)).thenReturn(List.of(encoded));
        when(headers.header(BeaconA2aHttpClient.BEACON_HEADER)).thenReturn(List.of());
        when(headers.header(BeaconA2aHttpClient.BINDING_HEADER)).thenReturn(List.of());
        when(headers.firstHeader(BeaconA2aHttpClient.AUTHORIZATION_HEADER)).thenReturn(encoded);
        return headers;
    }

    private static DelegatedAuthorizationRequest signedRequest(String body, String actor, long now,
                                                                 KeyPair authority, KeyPair agent) {
        return signedRequest(body, actor, now, authority, agent, List.of(OPERATION));
    }

    private static DelegatedAuthorizationRequest signedRequest(String body, String actor, long now,
                                                                 KeyPair authority, KeyPair agent,
                                                                 List<String> parentOperations) {
        String nonce = nonce((byte) 1);
        String hash = "a".repeat(64);
        String policy = "b".repeat(64);
        AuthorizationContext unsignedContext = new AuthorizationContext(actor, 4, 2, hash, parentOperations,
                "agent-service", "entry-request", "run-1", "session-1", policy, hash, nonce,
                now, now + 300, now + 300, AuthzContract.ALGORITHM, AUTH_KEY, null);
        AuthorizationContext context = AuthzSignature.sign(unsignedContext, authority.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness(actor, 4, 2, AuthorizationStatus.ACTIVE,
                hash, now, now + 30, nonce, AuthzContract.ALGORITHM, AUTH_KEY, null), authority.getPrivate());
        var facts = requestFacts(body);
        String requestNonce = randomNonce();
        long expiresAt = now + 20;
        DelegationBinding binding = new DelegationBinding("agent-service", "listing-master-service", actor,
                List.of(OPERATION), context.runId(), facts.taskId(), facts.requestId(),
                AuthzCanonicalHash.bodyHash(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                context.policyDigest(), requestNonce, now, expiresAt);
        String requestHash = AuthzCanonicalHash.delegationRequestHash(context, binding);
        CallerProof proof = AuthzSignature.sign(new CallerProof("agent-service", "listing-master-service",
                AuthzRpcOperation.VALIDATE_DELEGATION, binding.requestId(), requestHash, requestNonce,
                now, expiresAt, AuthzContract.ALGORITHM, AGENT_KEY, null), agent.getPrivate());
        return new DelegatedAuthorizationRequest(context, freshness, binding, proof);
    }

    private static A2aAuthorizationVerifier.RequestFacts requestFacts(String body) {
        try {
            var node = Utils.OBJECT_MAPPER.readTree(body);
            String method = node.path("method").asText();
            boolean sending = method.equals("message/send") || method.equals("message/stream");
            var params = node.path("params");
            return new A2aAuthorizationVerifier.RequestFacts(node.path("id").asText(), method,
                    sending ? params.path("message").path("taskId").asText() : params.path("id").asText(),
                    sending ? params.path("message").path("contextId").asText(null) : null, sending);
        } catch (Exception failure) { throw new IllegalArgumentException(failure); }
    }

    private static String sendBody(String task, String modelUserId) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"rpc-" + task + "\",\"method\":\"message/send\","
                + "\"params\":{\"message\":{\"taskId\":\"" + task + "\",\"contextId\":\"ctx-1\","
                + "\"metadata\":{\"userId\":\"" + modelUserId + "\"}},\"configuration\":{\"pushNotification\":null}}}";
    }

    private static String getBody(String task, String requestId) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId + "\",\"method\":\"tasks/get\","
                + "\"params\":{\"id\":\"" + task + "\"}}";
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String nonce(byte seed) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, seed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomNonce() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
