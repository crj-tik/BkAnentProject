package com.bkanent.auth;

import com.bkanent.auth.authorization.AuthorizationRepository;
import com.bkanent.auth.authorization.EffectivePermissionService;
import com.bkanent.auth.config.AuthAuthorizationProperties;
import com.bkanent.auth.rpc.AuthAuthorizationRpcServiceImpl;
import com.bkanent.auth.service.AuthTokenService;
import com.bkanent.common.authz.*;
import com.bkanent.common.security.ServiceIdentityCrypto;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthExecutionBindingTest {
    private static final long NOW = 1_800_000_000L;
    private static final String HASH = "a".repeat(64);
    private static final List<String> OPERATIONS = List.of("contract.seal.own", "settlement.batch.create.all");

    @Test
    void realProviderBindsUnboundIngressThenCreatesValidLocalRunSessionWithoutSecondAuthentication() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse ingress = f.ingress();
        AuthorizationSession original = f.session(ingress);
        f.clock.advance(10);
        ExecutionBinding binding = f.binding(ingress, 2);
        AuthzResponse bound = f.bind(ingress, binding);
        AuthzSignature.verifyExecutionBindingResponse(ingress, binding, bound, f.keys(), f.clock.instant());
        AuthorizationSession run = AuthorizationSession.fromEntry(bound, "agent-service",
                new AuthorizationSession.ResponseCorrelation(binding.requestId(),
                        AuthzCanonicalHash.executionBindingRequestHash(ingress, binding), binding.nonce()),
                new AuthorizationCache(8, 100_000, Duration.ofMinutes(5), 8), f.keys(), f.clock);
        assertThat(run.state()).isEqualTo(AuthorizationLocalState.VALID);
        assertThat(run.context().runId()).isEqualTo("run-1");
        assertThat(run.context().sessionId()).isEqualTo("session-1");
        assertThat(run.context().allowedOperations()).containsExactly("contract.seal.own");
        assertThat(run.context().permissionHash()).isEqualTo(ingress.context().permissionHash());
        assertThat(run.context().expiresAtEpochSecond()).isEqualTo(NOW + 300);
        assertThat(run.context().runHardDeadlineEpochSecond()).isEqualTo(NOW + 300);
        assertThat(bound.freshness().checkedAtEpochSecond()).isEqualTo(NOW + 10);
        assertThat(bound.freshness().notAfterEpochSecond()).isEqualTo(NOW + 40);
        assertThat(ingress.context().runId()).isNull();
        assertThatThrownBy(() -> original.refresh(bound, correlation(bound)))
                .isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
        verify(f.tokens, times(1)).parse("synthetic-access");
        verify(f.repository, times(1)).bindIngressExecution(eq(binding.ingressRequestHash()),
                eq(AuthzCanonicalHash.executionBindingTupleHash(ingress, binding)), eq(7L), eq("agent-service"),
                eq("run-1"), eq("session-1"), eq(HASH),
                eq(AuthzCanonicalHash.executionOperationsHash(binding.operations())), eq(HASH),
                eq(NOW + 300), eq(NOW + 300), eq(1L), eq(1L));
    }

    @Test
    void sameIngressSameTupleWithFreshNonceIsIdempotentButReplayAndDifferentTupleAreRejected() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse ingress = f.ingress();
        ExecutionBinding first = f.binding(ingress, 2);
        AuthzResponse response = f.bind(ingress, first);
        assertThatThrownBy(() -> f.bind(ingress, first)).isInstanceOf(AuthzException.class).hasMessage("REPLAY_DETECTED");
        f.clock.advance(5);
        ExecutionBinding retry = f.binding(ingress, 3);
        AuthzResponse repeated = f.bind(ingress, retry);
        assertThat(repeated.context().expiresAtEpochSecond()).isEqualTo(response.context().expiresAtEpochSecond());
        assertThat(repeated.context().runHardDeadlineEpochSecond()).isEqualTo(response.context().runHardDeadlineEpochSecond());
        assertThat(AuthzCanonicalHash.executionBindingTupleHash(ingress, retry))
                .isEqualTo(AuthzCanonicalHash.executionBindingTupleHash(ingress, first));
        assertThat(AuthzCanonicalHash.executionBindingRequestHash(ingress, retry))
                .isNotEqualTo(AuthzCanonicalHash.executionBindingRequestHash(ingress, first));
        for (int change = 0; change < 4; change++) {
            ExecutionBinding wrong = copy(retry, "7", "agent-service", change == 0 ? "run-2" : "run-1",
                    change == 1 ? "session-2" : "session-1", change == 2 ? OPERATIONS : retry.operations(),
                    retry.ingressRequestHash(), change == 3 ? "b".repeat(64) : HASH, HASH, 10 + change);
            assertThatThrownBy(() -> f.bind(ingress, wrong)).isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
        }
    }

    @Test
    void alreadyBoundResponseCannotBeBoundAgainEvenToSameIdentity() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse ingress = f.ingress();
        AuthzResponse bound = f.bind(ingress, f.binding(ingress, 2));
        ExecutionBinding again = f.binding(bound, 3);
        clearInvocations(f.repository, f.tokens);
        assertThatThrownBy(() -> f.bind(bound, again)).isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
        verifyNoInteractions(f.repository, f.tokens);
    }

    @Test
    void wrongActorCallerIngressHashPolicyAndExpandedScopeFailBeforeRepository() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse ingress = f.ingress();
        ExecutionBinding good = f.binding(ingress, 2);
        List<ExecutionBinding> wrong = List.of(
                copy(good, "8", "agent-service", "run-1", "session-1", good.operations(), good.ingressRequestHash(), HASH, HASH, 3),
                copy(good, "7", "gateway", "run-1", "session-1", good.operations(), good.ingressRequestHash(), HASH, HASH, 4),
                copy(good, "7", "agent-service", "run-1", "session-1", good.operations(), "b".repeat(64), HASH, HASH, 5),
                copy(good, "7", "agent-service", "run-1", "session-1", good.operations(), good.ingressRequestHash(), HASH, "b".repeat(64), 6),
                copy(good, "7", "agent-service", "run-1", "session-1", List.of("contract.payment.execute"), good.ingressRequestHash(), HASH, HASH, 7));
        clearInvocations(f.repository, f.tokens);
        for (ExecutionBinding binding : wrong) {
            assertThatThrownBy(() -> f.bind(ingress, binding)).isInstanceOf(AuthzException.class);
        }
        verifyNoInteractions(f.repository, f.tokens);
    }

    @Test
    void independentProofTypeAclKeyNonceAndExecutionHashAreEnforcedBeforeRepository() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse ingress = f.ingress();
        ExecutionBinding binding = f.binding(ingress, 2);
        CallerProof good = f.proof(ingress, binding);
        clearInvocations(f.repository, f.tokens);
        for (AuthzRpcOperation wrong : List.of(AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION,
                AuthzRpcOperation.REFRESH_AUTHORIZATION, AuthzRpcOperation.VALIDATE_DELEGATION)) {
            CallerProof proof = proofCopy(good, wrong, good.keyId(), good.nonce(), good.requestHash(), f.agent);
            assertThatThrownBy(() -> f.service.bindExecution(ingress, binding, proof))
                    .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        }
        for (CallerProof proof : List.of(
                proofCopy(good, good.operation(), "unknown-key", good.nonce(), good.requestHash(), f.agent),
                proofCopy(good, good.operation(), good.keyId(), good.nonce(), good.requestHash(), f.authority),
                proofCopy(good, good.operation(), good.keyId(), nonce(9), good.requestHash(), f.agent),
                proofCopy(good, good.operation(), good.keyId(), good.nonce(), "b".repeat(64), f.agent))) {
            assertThatThrownBy(() -> f.service.bindExecution(ingress, binding, proof))
                    .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        }
        ExecutionBinding changedInput = copy(binding, "7", "agent-service", "run-1", "session-1", binding.operations(),
                binding.ingressRequestHash(), "b".repeat(64), HASH, 2);
        assertThatThrownBy(() -> f.service.bindExecution(ingress, changedInput, good))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        var caller = f.properties.getTrustedCallers().get("agent-key");
        caller.setOperations(Set.of("REFRESH_AUTHORIZATION"));
        assertThatThrownBy(() -> f.bind(ingress, binding)).isInstanceOf(AuthzException.class).hasMessage("PERMISSION_DENIED");
        caller.setOperations(Set.of("BIND_EXECUTION"));
        caller.setPermissionCodes(Set.of("interview.read"));
        assertThatThrownBy(() -> f.bind(ingress, binding)).isInstanceOf(AuthzException.class).hasMessage("PERMISSION_DENIED");
        verifyNoInteractions(f.repository, f.tokens);
    }

    @Test
    void allThreeIngressSignaturesMustBeAuthenticNotJustPermissionHash() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse ingress = f.ingress();
        var snapshot = ingress.snapshot();
        var context = ingress.context();
        var freshness = ingress.freshness();
        List<AuthzResponse> forged = List.of(
                new AuthzResponse(AuthzSignature.sign(new AuthorizationSnapshot(snapshot.actorId(), snapshot.permissionRevision(),
                        snapshot.catalogVersion(), snapshot.businessAdmin(), snapshot.permissions(), snapshot.grantDeadlineEpochSecond(),
                        snapshot.algorithm(), snapshot.keyId(), null), f.agent.getPrivate()), context, freshness),
                new AuthzResponse(snapshot, AuthzSignature.sign(context, f.agent.getPrivate()), freshness),
                new AuthzResponse(snapshot, context, AuthzSignature.sign(freshness, f.agent.getPrivate())));
        clearInvocations(f.repository, f.tokens);
        for (AuthzResponse altered : forged) {
            assertThatThrownBy(() -> f.bind(altered, f.binding(altered, 2)))
                    .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        }
        verifyNoInteractions(f.repository, f.tokens);
    }

    @Test
    void revisionEpochStatusAndSameRevisionPermissionContentChangesFailClosed() throws Exception {
        for (int change = 0; change < 4; change++) {
            Fixture f = new Fixture();
            AuthzResponse ingress = f.ingress();
            when(f.repository.snapshot(eq(7L), any())).thenReturn(Optional.of(new EffectivePermissionService.Snapshot(
                    7, change == 0 ? 2 : 1, change == 1 ? 2 : 1, change == 2 ? "DISABLED" : "ACTIVE", false,
                    1, change == 3 ? List.of("interview.read") : OPERATIONS, null)));
            clearInvocations(f.repository);
            assertThatThrownBy(() -> f.bind(ingress, f.binding(ingress, 2))).isInstanceOf(AuthzException.class);
            verify(f.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
        }
    }

    @Test
    void originalFreshnessExpiryAndLatencyCannotBeRepairedOrExtendedByBinding() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse ingress = f.ingress();
        f.clock.advance(30);
        clearInvocations(f.repository);
        assertThatThrownBy(() -> f.bind(ingress, f.binding(ingress, 2)))
                .isInstanceOf(AuthzException.class).hasMessage("EXPIRED");
        verifyNoInteractions(f.repository);
        Fixture slow = new Fixture();
        AuthzResponse fresh = slow.ingress();
        when(slow.repository.snapshot(eq(7L), any())).thenAnswer(invocation -> {
            slow.clock.advance(30);
            return Optional.of(new EffectivePermissionService.Snapshot(7, 1, 1, "ACTIVE", false, 1, OPERATIONS, null));
        });
        clearInvocations(slow.repository);
        assertThatThrownBy(() -> slow.bind(fresh, slow.binding(fresh, 2)))
                .isInstanceOf(AuthzException.class).hasMessage("EXPIRED");
        verify(slow.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void shortenedIngressDeadlineRemainsFixedAcrossNewNonceRetries() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse ingress = f.ingress();
        var c = ingress.context();
        var shortened = AuthzSignature.sign(new AuthorizationContext(c.actorId(), c.permissionRevision(), c.credentialEpoch(),
                c.permissionHash(), c.allowedOperations(), c.audience(), c.requestId(), null, null, c.policyDigest(),
                c.requestHash(), c.nonce(), NOW, NOW + 20, c.runHardDeadlineEpochSecond(), c.algorithm(), c.keyId(), null), f.authority.getPrivate());
        AuthzResponse limited = new AuthzResponse(ingress.snapshot(), shortened, ingress.freshness());
        AuthzResponse first = f.bind(limited, f.binding(limited, 2));
        f.clock.advance(5);
        AuthzResponse retry = f.bind(limited, f.binding(limited, 3));
        assertThat(first.context().expiresAtEpochSecond()).isEqualTo(NOW + 20);
        assertThat(retry.context().expiresAtEpochSecond()).isEqualTo(NOW + 20);
        assertThat(retry.context().runHardDeadlineEpochSecond()).isEqualTo(NOW + 300);
        var tooLong = new ExecutionBinding("agent-service", "7", "run-1", "session-1", List.of("contract.seal.own"),
                c.requestHash(), HASH, HASH, "binding-4", nonce(4), NOW + 5, NOW + 21, AuthzContract.ALGORITHM);
        assertThatThrownBy(() -> f.bind(limited, tooLong)).isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
    }

    @Test
    void finalVersionRaceAndDurableStoreFailureNeverReturnAuthorization() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse ingress = f.ingress();
        when(f.repository.currentVersion(7)).thenReturn(
                Optional.of(new AuthorizationRepository.UserVersion(7, 1, 1, "ACTIVE")),
                Optional.of(new AuthorizationRepository.UserVersion(7, 2, 1, "ACTIVE")));
        assertThatThrownBy(() -> f.bind(ingress, f.binding(ingress, 2)))
                .isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
        Fixture outage = new Fixture();
        AuthzResponse admitted = outage.ingress();
        when(outage.repository.bindIngressExecution(anyString(), anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("synthetic binding store outage"));
        assertThatThrownBy(() -> outage.bind(admitted, outage.binding(admitted, 2)))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHORIZATION_UNAVAILABLE");
    }

    private static ExecutionBinding copy(ExecutionBinding b, String actor, String caller, String run, String session,
                                         List<String> operations, String ingressHash, String executionHash, String policy, int id) {
        return new ExecutionBinding(caller, actor, run, session, operations, ingressHash, executionHash, policy,
                "binding-" + id, nonce(id), b.issuedAtEpochSecond(), b.expiresAtEpochSecond(), AuthzContract.ALGORITHM);
    }

    private static CallerProof proofCopy(CallerProof p, AuthzRpcOperation operation, String kid, String nonce,
                                         String hash, KeyPair signer) {
        return AuthzSignature.sign(new CallerProof(p.callerService(), p.audience(), operation, p.requestId(), hash,
                nonce, p.issuedAtEpochSecond(), p.expiresAtEpochSecond(), p.algorithm(), kid, null), signer.getPrivate());
    }

    private static AuthorizationSession.ResponseCorrelation correlation(AuthzResponse response) {
        var c = response.context();
        return new AuthorizationSession.ResponseCorrelation(c.requestId(), c.requestHash(), c.nonce());
    }

    private static String nonce(int id) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.ofEpochSecond(NOW);
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        final KeyPair authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final KeyPair agent = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final KeyPair gateway = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final AuthTokenService tokens = mock(AuthTokenService.class);
        final AuthorizationRepository repository = mock(AuthorizationRepository.class);
        final AuthAuthorizationProperties properties = new AuthAuthorizationProperties() {
            @Override public java.security.PrivateKey signingKey() { return authority.getPrivate(); }
        };
        final AuthAuthorizationRpcServiceImpl service;

        Fixture() throws Exception {
            properties.setResponseKeyId("auth-key");
            properties.setResponsePublicKey(ServiceIdentityCrypto.encodePublicKey(authority.getPublic()));
            caller("gateway-key", "gateway", gateway, Set.of("RESOLVE_REQUEST_AUTHORIZATION"));
            caller("agent-key", "agent-service", agent, Set.of("BIND_EXECUTION"));
            when(tokens.parse("synthetic-access")).thenReturn(new AuthTokenService.TokenPrincipal(7, "access", NOW + 600));
            when(repository.snapshot(eq(7L), any())).thenReturn(Optional.of(new EffectivePermissionService.Snapshot(
                    7, 1, 1, "ACTIVE", false, 1, OPERATIONS, null)));
            when(repository.currentVersion(7)).thenReturn(Optional.of(new AuthorizationRepository.UserVersion(7, 1, 1, "ACTIVE")));
            when(repository.withWriterLock(any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get());
            Set<String> nonces = new HashSet<>();
            when(repository.consumeNonce(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation ->
                    nonces.add(invocation.getArgument(0) + ":" + invocation.getArgument(1) + ":" + invocation.getArgument(2)));
            Map<String, String> tuples = new HashMap<>();
            when(repository.bindIngressExecution(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong())).thenAnswer(invocation -> {
                String previous = tuples.putIfAbsent(invocation.getArgument(0), invocation.getArgument(1));
                return previous == null ? AuthorizationRepository.BindingResult.CREATED
                        : previous.equals(invocation.getArgument(1)) ? AuthorizationRepository.BindingResult.IDEMPOTENT
                        : AuthorizationRepository.BindingResult.CONFLICT;
            });
            service = new AuthAuthorizationRpcServiceImpl(tokens, repository, properties, clock);
        }

        void caller(String kid, String name, KeyPair key, Set<String> operations) {
            var caller = new AuthAuthorizationProperties.TrustedCaller();
            caller.setService(name);
            caller.setPublicKey(ServiceIdentityCrypto.encodePublicKey(key.getPublic()));
            caller.setOperations(operations);
            caller.setAudiences(Set.of("agent-service"));
            caller.setPermissionCodes(Set.copyOf(OPERATIONS));
            properties.getTrustedCallers().put(kid, caller);
        }

        AuthzResponse ingress() {
            var request = new RequestBinding("gateway", "agent-service", "POST", "/agent/chat", "/agent/chat",
                    List.of(), "entry-1", null, null, HASH, HASH, nonce(1), NOW, NOW + 60);
            var proof = AuthzSignature.sign(new CallerProof("gateway", "auth-service", AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION,
                    request.requestId(), AuthzCanonicalHash.resolveRequestHash("synthetic-access", request), request.nonce(),
                    NOW, NOW + 60, AuthzContract.ALGORITHM, "gateway-key", null), gateway.getPrivate());
            return service.resolveRequestAuthorization("synthetic-access", request, proof);
        }

        ExecutionBinding binding(AuthzResponse ingress, int id) {
            long now = clock.instant().getEpochSecond();
            return new ExecutionBinding("agent-service", "7", "run-1", "session-1", List.of("contract.seal.own"),
                    ingress.context().requestHash(), HASH, HASH, "binding-" + id, nonce(id), now,
                    Math.min(now + 60, ingress.context().expiresAtEpochSecond()), AuthzContract.ALGORITHM);
        }

        CallerProof proof(AuthzResponse ingress, ExecutionBinding binding) {
            return AuthzSignature.sign(new CallerProof(binding.callerService(), "auth-service", AuthzRpcOperation.BIND_EXECUTION,
                    binding.requestId(), AuthzCanonicalHash.executionBindingRequestHash(ingress, binding), binding.nonce(),
                    binding.issuedAtEpochSecond(), binding.expiresAtEpochSecond(), AuthzContract.ALGORITHM, "agent-key", null), agent.getPrivate());
        }

        AuthzResponse bind(AuthzResponse ingress, ExecutionBinding binding) {
            return service.bindExecution(ingress, binding, proof(ingress, binding));
        }

        Map<String, PublicKey> keys() { return Map.of("auth-key", authority.getPublic()); }

        AuthorizationSession session(AuthzResponse ingress) {
            return AuthorizationSession.fromEntry(ingress, "agent-service", correlation(ingress),
                    new AuthorizationCache(8, 100_000, Duration.ofMinutes(5), 8), keys(), clock);
        }
    }
}
