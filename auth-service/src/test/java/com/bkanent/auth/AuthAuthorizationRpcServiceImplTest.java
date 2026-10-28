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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthAuthorizationRpcServiceImplTest {
    private static final String TOKEN = "synthetic-access";
    private static final String HASH = "a".repeat(64);
    private static final List<String> PERMISSIONS = List.of("contract.seal.own", "interview.read");
    private static final List<String> BASELINE_OPERATIONS = AuthzOperationPolicy.contextOperations(List.of());

    @Test
    void entryAuthenticatesOnceAndReturnsRequestBoundSignedAuthority() throws Exception {
        Fixture f = new Fixture();
        var response = f.service.resolveRequestAuthorization(TOKEN, f.request, f.entryProof());
        AuthzSignature.verifyResponse(response, "agent-service", f.request.requestId(),
                AuthzCanonicalHash.resolveRequestHash(TOKEN, f.request), f.request.nonce(), f.authorityKeys(), f.clock.instant());
        assertThat(response.snapshot().permissions()).containsExactly("contract.seal.own");
        assertThat(response.context().allowedOperations()).containsAll(BASELINE_OPERATIONS)
                .contains("contract.seal.own");
        assertThat(response.context().runHardDeadlineEpochSecond()).isLessThanOrEqualTo(f.now + 300);
        verify(f.tokens, times(1)).parse(TOKEN);
        verify(f.repository, times(1)).snapshot(eq(7L), any(Instant.class));
    }

    @Test
    void changedBodyFailsBeforeUserLookup() throws Exception {
        Fixture f = new Fixture();
        var proof = f.entryProof();
        var altered = new RequestBinding("gateway", "agent-service", "POST", "/agent/chat", "/agent/chat",
                List.of(), "entry-1", "run-1", "session-1", "b".repeat(64), HASH, f.request.nonce(), f.now, f.now + 60);
        assertThatThrownBy(() -> f.service.resolveRequestAuthorization(TOKEN, altered, proof))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        verifyNoInteractions(f.tokens, f.repository);
    }

    @Test
    void delegationRequiresIndependentOriginAndReceiverProofs() throws Exception {
        Fixture f = new Fixture();
        var entry = f.service.resolveRequestAuthorization(TOKEN, f.request, f.entryProof());
        var binding = new DelegationBinding("agent-service", "interview-service", "7", List.of("interview.read"),
                "run-1", "child-1", "delegation-1", HASH, HASH, nonce(2),
                entry.context().issuedAtEpochSecond(), entry.context().issuedAtEpochSecond() + 60);
        String hash = AuthzCanonicalHash.delegationRequestHash(entry.context(), binding);
        var origin = AuthzSignature.sign(new CallerProof("agent-service", "interview-service",
                AuthzRpcOperation.VALIDATE_DELEGATION, "delegation-1", hash, binding.nonce(), binding.issuedAtEpochSecond(),
                binding.expiresAtEpochSecond(), AuthzContract.ALGORITHM, "agent-key", null), f.agent.getPrivate());
        var receiver = AuthzSignature.sign(new CallerProof("interview-service", "auth-service",
                AuthzRpcOperation.VALIDATE_DELEGATION, "delegation-1", hash, nonce(3), binding.issuedAtEpochSecond(),
                binding.expiresAtEpochSecond(), AuthzContract.ALGORITHM, "interview-key", null), f.interview.getPrivate());
        var child = f.service.validateDelegation(entry.context(), binding, origin, receiver);
        AuthzSignature.verifyChildContext(entry.context(), child.context(), binding, f.authorityKeys(), f.clock.instant());
        assertThat(child.context().allowedOperations()).containsExactly("interview.read");
        assertThatThrownBy(() -> f.service.validateDelegation(entry.context(), binding, receiver, receiver))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
    }

    @Test
    void staleParentRevisionCannotBeRenewedByAValidService() throws Exception {
        Fixture f = new Fixture();
        var entry = f.service.resolveRequestAuthorization(TOKEN, f.request, f.entryProof());
        when(f.repository.snapshot(eq(7L), any())).thenReturn(Optional.of(new EffectivePermissionService.Snapshot(
                7, 2, 1, "ACTIVE", false, 1, PERMISSIONS, null)));
        when(f.repository.currentVersion(7)).thenReturn(Optional.of(new AuthorizationRepository.UserVersion(7, 2, 1, "ACTIVE")));
        var binding = new DelegationBinding("agent-service", "interview-service", "7", List.of("interview.read"),
                "run-1", "child-1", "delegation-1", HASH, HASH, nonce(2),
                entry.context().issuedAtEpochSecond(), entry.context().issuedAtEpochSecond() + 60);
        String hash = AuthzCanonicalHash.delegationRequestHash(entry.context(), binding);
        var source = AuthzSignature.sign(new CallerProof("agent-service", "interview-service",
                AuthzRpcOperation.VALIDATE_DELEGATION, "delegation-1", hash, binding.nonce(), binding.issuedAtEpochSecond(),
                binding.expiresAtEpochSecond(), AuthzContract.ALGORITHM, "agent-key", null), f.agent.getPrivate());
        var target = AuthzSignature.sign(new CallerProof("interview-service", "auth-service",
                AuthzRpcOperation.VALIDATE_DELEGATION, "delegation-1", hash, nonce(3), binding.issuedAtEpochSecond(),
                binding.expiresAtEpochSecond(), AuthzContract.ALGORITHM, "interview-key", null), f.interview.getPrivate());
        assertThatThrownBy(() -> f.service.validateDelegation(entry.context(), binding, source, target))
                .isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
    }

    @Test
    void replayAndUnregisteredCallerFailClosed() throws Exception {
        Fixture f = new Fixture();
        when(f.repository.consumeNonce(anyString(), anyString(), anyString(), any(), any())).thenReturn(false);
        assertThatThrownBy(() -> f.service.resolveRequestAuthorization(TOKEN, f.request, f.entryProof()))
                .isInstanceOf(AuthzException.class).hasMessage("REPLAY_DETECTED");
        f.properties.getTrustedCallers().clear();
        assertThatThrownBy(() -> f.service.resolveRequestAuthorization(TOKEN, f.request, f.entryProof()))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
    }

    @Test
    void sameRunRefreshAtTenSecondsInstallsNewVersionThenDelegatesOnlyRemainingScope() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse entry = f.entry();
        AuthorizationSession session = f.session(entry);
        f.clock.advance(10);
        f.version(2, 1, List.of("settlement.batch.create.all", "interview.read", "memory.read"), null, false);
        session.onNotification("7", 2, 1);
        assertThat(session.state()).isEqualTo(AuthorizationLocalState.DIRTY);
        assertThatThrownBy(() -> f.delegate(entry.context(), List.of("interview.read"), 20))
                .isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
        RequestBinding refreshBinding = f.refreshBinding(entry.context(), 10);
        String refreshHash = AuthzCanonicalHash.refreshRequestHash(TOKEN, entry.context(), refreshBinding);
        AuthzResponse refreshed = f.service.refreshAuthorization(TOKEN, entry.context(), refreshBinding,
                f.refreshProof(TOKEN, entry.context(), refreshBinding));
        session.refresh(refreshed, new AuthorizationSession.ResponseCorrelation(refreshBinding.requestId(),
                refreshHash, refreshBinding.nonce()));
        assertThat(session.state()).isEqualTo(AuthorizationLocalState.VALID);
        assertThat(session.context().permissionRevision()).isEqualTo(2);
        assertThat(session.context().issuedAtEpochSecond()).isEqualTo(f.now + 10);
        assertThat(session.context().runHardDeadlineEpochSecond()).isEqualTo(f.now + 300);
        assertThat(session.context().expiresAtEpochSecond()).isEqualTo(f.now + 300);
        assertThat(refreshed.snapshot().permissions()).containsExactly("settlement.batch.create.all");
        assertThat(refreshed.context().allowedOperations()).containsExactlyElementsOf(BASELINE_OPERATIONS);
        assertThat(refreshed.freshness().checkedAtEpochSecond()).isEqualTo(f.now + 10);
        assertThat(refreshed.freshness().notAfterEpochSecond()).isEqualTo(f.now + 40);
        var child = f.delegate(session.context(), List.of("interview.read"), 21);
        assertThat(child.context().runHardDeadlineEpochSecond()).isEqualTo(f.now + 300);
        assertThat(child.context().expiresAtEpochSecond()).isLessThanOrEqualTo(session.context().expiresAtEpochSecond());
        assertThatThrownBy(() -> f.delegate(session.context(), List.of("contract.seal.own"), 22))
                .isInstanceOf(AuthzException.class).hasMessage("PERMISSION_DENIED");
        assertThatThrownBy(() -> session.requirePermission("contract.seal.own"))
                .isInstanceOf(AuthzException.class).hasMessage("PERMISSION_DENIED");
    }

    @Test
    void refreshCanRepairExpiredFreshnessButCannotRepairExpiredOriginalContext() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse entry = f.entry();
        AuthorizationSession session = f.session(entry);
        f.clock.advance(31);
        assertThat(session.state()).isEqualTo(AuthorizationLocalState.EXPIRED);
        f.version(2, 1, PERMISSIONS, null, false);
        AuthzResponse refreshed = f.refresh(entry.context(), 10);
        session.refresh(refreshed, correlation(refreshed));
        assertThat(session.state()).isEqualTo(AuthorizationLocalState.VALID);
        RequestBinding binding = f.refreshBinding(refreshed.context(), 11);
        CallerProof proof = f.refreshProof(TOKEN, refreshed.context(), binding);
        f.clock.advance(269);
        clearInvocations(f.tokens, f.repository);
        assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, refreshed.context(), binding, proof))
                .isInstanceOf(AuthzException.class).hasMessage("EXPIRED");
        verifyNoInteractions(f.tokens, f.repository);
    }

    @Test
    void refreshCannotChangeActorAudienceRunSessionPolicyOrOriginalSignature() throws Exception {
        Fixture f = new Fixture();
        AuthorizationContext original = f.entry().context();
        f.clock.advance(10);
        RequestBinding binding = f.refreshBinding(original, 10);
        List<RequestBinding> mismatches = List.of(
                requestCopy(binding, "agent-service", "interview-service", binding.runId(), binding.sessionId(), HASH, HASH),
                requestCopy(binding, "gateway", "agent-service", binding.runId(), binding.sessionId(), HASH, HASH),
                requestCopy(binding, "agent-service", "agent-service", "different-run", binding.sessionId(), HASH, HASH),
                requestCopy(binding, "agent-service", "agent-service", binding.runId(), "different-session", HASH, HASH),
                requestCopy(binding, "agent-service", "agent-service", binding.runId(), binding.sessionId(), "b".repeat(64), HASH));
        for (RequestBinding wrong : mismatches) {
            clearInvocations(f.tokens, f.repository);
            assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, original, wrong, f.refreshProof(TOKEN, original, wrong)))
                    .isInstanceOf(AuthzException.class);
            verifyNoInteractions(f.tokens, f.repository);
        }
        AuthorizationContext forged = contextCopy(original, "8", original.expiresAtEpochSecond(), original.signature());
        clearInvocations(f.tokens, f.repository);
        assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, forged, binding, f.refreshProof(TOKEN, forged, binding)))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        verifyNoInteractions(f.tokens, f.repository);
        when(f.tokens.parse(TOKEN)).thenReturn(new AuthTokenService.TokenPrincipal(8, "access", f.now + 600));
        assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, original, binding, f.refreshProof(TOKEN, original, binding)))
                .isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
        verify(f.repository, never()).snapshot(anyLong(), any());
        verify(f.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void refreshProofTypeAclUnknownKeyWrongKeyAndTamperedBodyFailBeforeTokenParse() throws Exception {
        Fixture f = new Fixture();
        AuthorizationContext original = f.entry().context();
        f.clock.advance(10);
        RequestBinding binding = f.refreshBinding(original, 10);
        CallerProof valid = f.refreshProof(TOKEN, original, binding);
        for (AuthzRpcOperation wrong : List.of(AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION, AuthzRpcOperation.VALIDATE_DELEGATION)) {
            CallerProof proof = AuthzSignature.sign(new CallerProof(valid.callerService(), valid.audience(), wrong,
                    valid.requestId(), valid.requestHash(), valid.nonce(), valid.issuedAtEpochSecond(), valid.expiresAtEpochSecond(),
                    valid.algorithm(), valid.keyId(), null), f.agent.getPrivate());
            clearInvocations(f.tokens, f.repository);
            assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, original, binding, proof))
                    .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
            verifyNoInteractions(f.tokens, f.repository);
        }
        for (String kid : List.of("unknown-key", "agent-key")) {
            CallerProof badKey = AuthzSignature.sign(new CallerProof(valid.callerService(), valid.audience(), valid.operation(),
                    valid.requestId(), valid.requestHash(), valid.nonce(), valid.issuedAtEpochSecond(), valid.expiresAtEpochSecond(),
                    valid.algorithm(), kid, null), f.gateway.getPrivate());
            assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, original, binding, badKey))
                    .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        }
        CallerProof wrongNonce = AuthzSignature.sign(new CallerProof(valid.callerService(), valid.audience(), valid.operation(),
                valid.requestId(), valid.requestHash(), nonce(11), valid.issuedAtEpochSecond(), valid.expiresAtEpochSecond(),
                valid.algorithm(), valid.keyId(), null), f.agent.getPrivate());
        assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, original, binding, wrongNonce))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        RequestBinding wrongBody = requestCopy(binding, binding.callerService(), binding.audience(), binding.runId(),
                binding.sessionId(), binding.policyDigest(), "b".repeat(64));
        assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, original, wrongBody, valid))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        var caller = f.properties.getTrustedCallers().get("agent-key");
        caller.setOperations(Set.of("VALIDATE_DELEGATION"));
        assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, original, binding, valid))
                .isInstanceOf(AuthzException.class).hasMessage("PERMISSION_DENIED");
        caller.setOperations(Set.of("REFRESH_AUTHORIZATION"));
        caller.setAudiences(Set.of("interview-service"));
        assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, original, binding, valid))
                .isInstanceOf(AuthzException.class).hasMessage("PERMISSION_DENIED");
        verifyNoInteractions(f.tokens, f.repository);
    }

    @Test
    void durableRefreshNonceReplayDoesNotIssueAnotherResponse() throws Exception {
        Fixture f = new Fixture();
        AuthorizationContext original = f.entry().context();
        f.clock.advance(10);
        RequestBinding binding = f.refreshBinding(original, 10);
        CallerProof proof = f.refreshProof(TOKEN, original, binding);
        f.service.refreshAuthorization(TOKEN, original, binding, proof);
        assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, original, binding, proof))
                .isInstanceOf(AuthzException.class).hasMessage("REPLAY_DETECTED");
        verify(f.repository, times(2)).consumeNonce(eq("agent-service"), eq("agent-key"), matches("[a-f0-9]{64}"), any(), any());
    }

    @Test
    void credentialEpochChangeRevisionRollbackAndSameRevisionContentChangeAreConflicts() throws Exception {
        Fixture f = new Fixture();
        f.version(2, 1, PERMISSIONS, null, false);
        AuthorizationContext original = f.entry().context();
        f.clock.advance(10);
        for (long[] version : List.of(new long[]{3, 2}, new long[]{1, 1})) {
            f.version(version[0], version[1], PERMISSIONS, null, false);
            clearInvocations(f.repository);
            assertThatThrownBy(() -> f.refresh(original, 10)).isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
            verify(f.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
        }
        f.version(2, 1, List.of("interview.read"), null, false);
        assertThatThrownBy(() -> f.refresh(original, 10)).isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
    }

    @Test
    void accessExpiryWrongTokenTypeAndDisabledAccountFailWithoutConsumingNonce() throws Exception {
        Fixture f = new Fixture();
        AuthorizationContext original = f.entry().context();
        f.clock.advance(10);
        for (AuthTokenService.TokenPrincipal invalid : List.of(
                new AuthTokenService.TokenPrincipal(7, "access", f.now + 10),
                new AuthTokenService.TokenPrincipal(7, "refresh", f.now + 600))) {
            when(f.tokens.parse(TOKEN)).thenReturn(invalid);
            clearInvocations(f.repository);
            assertThatThrownBy(() -> f.refresh(original, 10)).isInstanceOf(AuthzException.class);
            verify(f.repository, never()).snapshot(anyLong(), any());
            verify(f.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
        }
        when(f.tokens.parse(TOKEN)).thenReturn(new AuthTokenService.TokenPrincipal(7, "access", f.now + 600));
        when(f.repository.snapshot(eq(7L), any())).thenReturn(Optional.of(new EffectivePermissionService.Snapshot(
                7, 2, 1, "DISABLED", false, 1, PERMISSIONS, null)));
        assertThatThrownBy(() -> f.refresh(original, 10)).isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        verify(f.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void refreshPreservesEarlierOriginalExpiryAndTightensAccessGrantAndChildDeadlines() throws Exception {
        Fixture f = new Fixture();
        f.version(1, 1, PERMISSIONS, f.now + 200, false);
        AuthorizationContext entry = f.entry().context();
        AuthorizationContext original = AuthzSignature.sign(contextCopy(entry, entry.actorId(), f.now + 150, null), f.authority.getPrivate());
        f.clock.advance(10);
        f.version(2, 1, PERMISSIONS, null, false);
        AuthzResponse preserved = f.refresh(original, 10);
        assertThat(preserved.context().expiresAtEpochSecond()).isEqualTo(f.now + 150);
        assertThat(preserved.context().runHardDeadlineEpochSecond()).isEqualTo(f.now + 200);
        when(f.tokens.parse(TOKEN)).thenReturn(new AuthTokenService.TokenPrincipal(7, "access", f.now + 120));
        f.version(3, 1, PERMISSIONS, f.now + 100, false);
        AuthzResponse tightened = f.refresh(preserved.context(), 11);
        assertThat(tightened.context().expiresAtEpochSecond()).isEqualTo(f.now + 100);
        assertThat(tightened.context().runHardDeadlineEpochSecond()).isEqualTo(f.now + 200);
        f.clock.advance(80);
        AuthzResponse child = f.delegate(tightened.context(), List.of("interview.read"), 20);
        assertThat(child.context().expiresAtEpochSecond()).isEqualTo(f.now + 100);
        assertThat(child.context().runHardDeadlineEpochSecond()).isEqualTo(f.now + 200);
        f.clock.advance(10);
        assertThatThrownBy(() -> AuthzSignature.verifyContext(child.context(), "interview-service", f.authorityKeys(), f.clock.instant()))
                .isInstanceOf(AuthzException.class).hasMessage("EXPIRED");
    }

    @Test
    void accessDeadlineAloneTightensRefreshWithoutChangingRunDeadline() throws Exception {
        Fixture f = new Fixture();
        AuthorizationContext original = f.entry().context();
        f.clock.advance(10);
        when(f.tokens.parse(TOKEN)).thenReturn(new AuthTokenService.TokenPrincipal(7, "access", f.now + 80));
        AuthzResponse refreshed = f.refresh(original, 10);
        assertThat(refreshed.context().expiresAtEpochSecond()).isEqualTo(f.now + 80);
        assertThat(refreshed.context().runHardDeadlineEpochSecond()).isEqualTo(f.now + 300);
    }

    @Test
    void sameVersionCacheRebuildDoesNotChangeStableHashOrOriginalDeadline() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse entry = f.entry();
        AuthorizationSession session = f.session(entry);
        f.clock.advance(10);
        AuthzResponse rebuilt = f.refresh(entry.context(), 10);
        session.refresh(rebuilt, correlation(rebuilt));
        assertThat(rebuilt.snapshot()).isEqualTo(entry.snapshot());
        assertThat(rebuilt.context().permissionHash()).isEqualTo(entry.context().permissionHash());
        assertThat(rebuilt.context().expiresAtEpochSecond()).isEqualTo(f.now + 300);
        assertThat(rebuilt.context().runHardDeadlineEpochSecond()).isEqualTo(f.now + 300);
        assertThat(rebuilt.freshness().checkedAtEpochSecond()).isEqualTo(f.now + 10);
    }

    @Test
    void scopeAdditionsAdminPromotionAndRegrantCannotExpandRefreshedScope() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse entry = f.entry();
        AuthorizationSession session = f.session(entry);
        f.clock.advance(10);
        f.version(2, 1, List.of("settlement.batch.create.all", "interview.read", "memory.read"), null, true);
        AuthzResponse restricted = f.refresh(entry.context(), 10);
        session.refresh(restricted, correlation(restricted));
        assertThat(restricted.snapshot().businessAdmin()).isTrue();
        assertThat(session.allowedOperations()).containsExactlyElementsOf(BASELINE_OPERATIONS);
        f.clock.advance(10);
        f.version(3, 1, List.of("contract.seal.own", "interview.read", "memory.read"), null, true);
        AuthzResponse regranted = f.refresh(session.context(), 11);
        session.refresh(regranted, correlation(regranted));
        assertThat(session.allowedOperations()).containsExactlyElementsOf(BASELINE_OPERATIONS);
        f.clock.advance(10);
        f.version(4, 1, List.of("memory.read"), null, false);
        AuthzResponse empty = f.refresh(session.context(), 12);
        session.refresh(empty, correlation(empty));
        assertThat(session.state()).isEqualTo(AuthorizationLocalState.VALID);
        assertThat(session.allowedOperations()).containsExactlyElementsOf(BASELINE_OPERATIONS);
        assertThatThrownBy(() -> session.requirePermission("settlement.batch.create.all"))
                .isInstanceOf(AuthzException.class).hasMessage("PERMISSION_DENIED");
    }

    @Test
    void sessionRejectsProviderRefreshFromOlderWiderContextAfterRevocationAndRegrant() throws Exception {
        Fixture f = new Fixture();
        AuthzResponse c1 = f.entry();
        AuthorizationSession session = f.session(c1);
        f.clock.advance(10);
        f.version(2, 1, List.of("interview.read"), null, false);
        RequestBinding revokeBinding = f.refreshBinding(c1.context(), 10);
        var revokeCorrelation = new AuthorizationSession.ResponseCorrelation(revokeBinding.requestId(),
                AuthzCanonicalHash.refreshRequestHash(TOKEN, c1.context(), revokeBinding), revokeBinding.nonce());
        AuthzResponse c2 = f.service.refreshAuthorization(TOKEN, c1.context(), revokeBinding,
                f.refreshProof(TOKEN, c1.context(), revokeBinding));
        session.refresh(c2, revokeCorrelation);
        assertThat(session.allowedOperations()).containsExactlyElementsOf(BASELINE_OPERATIONS);
        f.clock.advance(10);
        f.version(3, 1, PERMISSIONS, null, false);
        session.onNotification("7", 3, 1);
        // The stateless provider cannot know this run already accepted narrower C2.
        // A holder can submit still-valid C1; local session must reject that restored scope.
        AuthzSignature.verifyContext(c1.context(), "agent-service", f.authorityKeys(), f.clock.instant());
        RequestBinding attackBinding = f.refreshBinding(c1.context(), 11);
        var attackCorrelation = new AuthorizationSession.ResponseCorrelation(attackBinding.requestId(),
                AuthzCanonicalHash.refreshRequestHash(TOKEN, c1.context(), attackBinding), attackBinding.nonce());
        AuthzResponse c3 = f.service.refreshAuthorization(TOKEN, c1.context(), attackBinding,
                f.refreshProof(TOKEN, c1.context(), attackBinding));
        AuthzSignature.verifyResponse(c3, "agent-service", attackCorrelation.requestId(), attackCorrelation.requestHash(),
                attackCorrelation.nonce(), f.authorityKeys(), f.clock.instant());
        assertThat(c3.context().allowedOperations()).containsExactlyElementsOf(
                AuthzOperationPolicy.contextOperations(PERMISSIONS));
        assertThatThrownBy(() -> session.refresh(c3, attackCorrelation))
                .isInstanceOf(AuthzException.class).hasMessage("PERMISSION_DENIED");
        assertThat(session.context()).isSameAs(c2.context());
        assertThat(session.snapshot()).isSameAs(c2.snapshot());
        assertThat(session.freshness()).isSameAs(c2.freshness());
        assertThat(session.allowedOperations()).containsExactlyElementsOf(BASELINE_OPERATIONS);
        assertThat(session.state()).isEqualTo(AuthorizationLocalState.DIRTY);
        RequestBinding safeBinding = f.refreshBinding(session.context(), 12);
        var safeCorrelation = new AuthorizationSession.ResponseCorrelation(safeBinding.requestId(),
                AuthzCanonicalHash.refreshRequestHash(TOKEN, session.context(), safeBinding), safeBinding.nonce());
        AuthzResponse limited = f.service.refreshAuthorization(TOKEN, session.context(), safeBinding,
                f.refreshProof(TOKEN, session.context(), safeBinding));
        session.refresh(limited, safeCorrelation);
        assertThat(session.state()).isEqualTo(AuthorizationLocalState.VALID);
        assertThat(session.context().permissionRevision()).isEqualTo(3);
        assertThat(session.allowedOperations()).containsExactlyElementsOf(BASELINE_OPERATIONS);
        assertThat(session.context().runHardDeadlineEpochSecond()).isEqualTo(f.now + 300);
        assertThatThrownBy(() -> session.requirePermission("contract.seal.own"))
                .isInstanceOf(AuthzException.class).hasMessage("PERMISSION_DENIED");
    }

    @Test
    void authorityChangeDuringFinalRecheckReturnsConflictBeforeNonceConsumption() throws Exception {
        Fixture f = new Fixture();
        AuthorizationContext original = f.entry().context();
        f.clock.advance(10);
        f.version(2, 1, PERMISSIONS, null, false);
        when(f.repository.currentVersion(7)).thenReturn(
                Optional.of(new AuthorizationRepository.UserVersion(7, 2, 1, "ACTIVE")),
                Optional.of(new AuthorizationRepository.UserVersion(7, 3, 1, "ACTIVE")));
        clearInvocations(f.repository);
        assertThatThrownBy(() -> f.refresh(original, 10)).isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
        verify(f.repository, times(2)).currentVersion(7);
        verify(f.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void authorityLatencyCannotExtendFreshnessOrAdmitExpiredProof() throws Exception {
        Fixture f = new Fixture();
        AuthorizationContext original = f.entry().context();
        f.clock.advance(10);
        when(f.repository.snapshot(eq(7L), any())).thenAnswer(invocation -> {
            f.clock.advance(30);
            return Optional.of(new EffectivePermissionService.Snapshot(7, 2, 1, "ACTIVE", false, 1, PERMISSIONS, null));
        });
        when(f.repository.currentVersion(7)).thenReturn(Optional.of(new AuthorizationRepository.UserVersion(7, 2, 1, "ACTIVE")));
        clearInvocations(f.repository);
        assertThatThrownBy(() -> f.refresh(original, 10)).isInstanceOf(AuthzException.class).hasMessage("EXPIRED");
        verify(f.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void tokenParseAndAuthorizationStateFailuresAreUnavailableAndCannotConsumeNonce() throws Exception {
        Fixture f = new Fixture();
        AuthorizationContext original = f.entry().context();
        f.clock.advance(10);
        when(f.tokens.parse(TOKEN)).thenThrow(new IllegalStateException("synthetic token store outage"));
        clearInvocations(f.repository);
        assertThatThrownBy(() -> f.refresh(original, 10)).isInstanceOf(AuthzException.class).hasMessage("AUTHORIZATION_UNAVAILABLE");
        verify(f.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
        doReturn(new AuthTokenService.TokenPrincipal(7, "access", f.now + 600)).when(f.tokens).parse(TOKEN);
        when(f.repository.snapshot(eq(7L), any())).thenThrow(new IllegalStateException("synthetic state outage"));
        assertThatThrownBy(() -> f.refresh(original, 10)).isInstanceOf(AuthzException.class).hasMessage("AUTHORIZATION_UNAVAILABLE");
        f.version(2, 1, PERMISSIONS, null, false);
        when(f.repository.currentVersion(7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> f.refresh(original, 10)).isInstanceOf(AuthzException.class).hasMessage("AUTHORIZATION_UNAVAILABLE");
        verify(f.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void originalContextExpiryEarlierThanRunDeadlineStillBlocksRefresh() throws Exception {
        Fixture f = new Fixture();
        AuthorizationContext entry = f.entry().context();
        AuthorizationContext original = AuthzSignature.sign(contextCopy(entry, entry.actorId(), f.now + 100, null), f.authority.getPrivate());
        f.clock.advance(90);
        RequestBinding binding = f.refreshBinding(original, 10);
        CallerProof proof = f.refreshProof(TOKEN, original, binding);
        f.clock.advance(10);
        clearInvocations(f.tokens, f.repository);
        assertThatThrownBy(() -> f.service.refreshAuthorization(TOKEN, original, binding, proof))
                .isInstanceOf(AuthzException.class).hasMessage("EXPIRED");
        verifyNoInteractions(f.tokens, f.repository);
    }

    @Test
    void pendingLogoutStateDependencyAndDurableNonceStoreFailuresAreUnavailable() throws Exception {
        Fixture f = new Fixture();
        AuthorizationContext original = f.entry().context();
        f.clock.advance(10);
        when(f.repository.isLogoutRecorded(anyString())).thenThrow(new IllegalStateException("synthetic logout state outage"));
        clearInvocations(f.tokens, f.repository);
        assertThatThrownBy(() -> f.refresh(original, 10))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHORIZATION_UNAVAILABLE");
        verifyNoInteractions(f.tokens);
        verify(f.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
        doReturn(false).when(f.repository).isLogoutRecorded(anyString());
        when(f.repository.consumeNonce(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("synthetic nonce store outage"));
        assertThatThrownBy(() -> f.refresh(original, 10))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHORIZATION_UNAVAILABLE");
    }

    @Test
    void durableLogoutRecordBlocksResolveAndRefreshBeforeTokenParse() throws Exception {
        Fixture f = new Fixture();
        AuthorizationContext original = f.entry().context();
        f.clock.advance(10);
        String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(TOKEN.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(f.repository.isLogoutRecorded(digest)).thenReturn(true);
        clearInvocations(f.tokens, f.repository);
        assertThatThrownBy(() -> f.refresh(original, 10)).isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        assertThatThrownBy(() -> f.service.resolveRequestAuthorization(TOKEN, f.request, f.entryProof()))
                .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        verifyNoInteractions(f.tokens);
        verify(f.repository, times(2)).isLogoutRecorded(digest);
        verify(f.repository, never()).snapshot(anyLong(), any());
        verify(f.repository, never()).consumeNonce(anyString(), anyString(), anyString(), any(), any());
    }

    private static AuthorizationSession.ResponseCorrelation correlation(AuthzResponse response) {
        var context = response.context();
        return new AuthorizationSession.ResponseCorrelation(context.requestId(), context.requestHash(), context.nonce());
    }

    private static RequestBinding requestCopy(RequestBinding original, String caller, String audience, String run,
                                              String session, String policy, String body) {
        return new RequestBinding(caller, audience, original.method(), original.originalPath(), original.targetPath(),
                original.query(), original.requestId(), run, session, body, policy, original.nonce(),
                original.issuedAtEpochSecond(), original.expiresAtEpochSecond());
    }

    private static AuthorizationContext contextCopy(AuthorizationContext original, String actor, long expiry, String signature) {
        return new AuthorizationContext(actor, original.permissionRevision(), original.credentialEpoch(), original.permissionHash(),
                original.allowedOperations(), original.audience(), original.requestId(), original.runId(), original.sessionId(),
                original.policyDigest(), original.requestHash(), original.nonce(), original.issuedAtEpochSecond(), expiry,
                original.runHardDeadlineEpochSecond(), original.algorithm(), original.keyId(), signature);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(long epochSecond) { this.now = Instant.ofEpochSecond(epochSecond); }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static String nonce(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class Fixture {
        final long now = 1_800_000_000L;
        final MutableClock clock = new MutableClock(now);
        final KeyPair authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final KeyPair gateway = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final KeyPair agent = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final KeyPair interview = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final AuthTokenService tokens = mock(AuthTokenService.class);
        final AuthorizationRepository repository = mock(AuthorizationRepository.class);
        final AuthAuthorizationProperties properties = new AuthAuthorizationProperties() {
            @Override public java.security.PrivateKey signingKey() { return authority.getPrivate(); }
        };
        final RequestBinding request = new RequestBinding("gateway", "agent-service", "POST", "/agent/chat", "/agent/chat",
                List.of(), "entry-1", "run-1", "session-1", HASH, HASH, nonce(1), now, now + 60);
        final AuthAuthorizationRpcServiceImpl service;

        Fixture() throws Exception {
            properties.setResponseKeyId("auth-key");
            properties.setResponsePublicKey(ServiceIdentityCrypto.encodePublicKey(authority.getPublic()));
            register("gateway-key", "gateway", gateway, Set.of("agent-service"));
            register("agent-key", "agent-service", agent, Set.of("agent-service", "interview-service"));
            register("interview-key", "interview-service", interview, Set.of("interview-service"));
            when(tokens.parse(TOKEN)).thenReturn(new AuthTokenService.TokenPrincipal(7, "access", now + 600));
            when(repository.snapshot(eq(7L), any())).thenReturn(Optional.of(new EffectivePermissionService.Snapshot(
                    7, 1, 1, "ACTIVE", false, 1, PERMISSIONS, null)));
            when(repository.currentVersion(7)).thenReturn(Optional.of(new AuthorizationRepository.UserVersion(7, 1, 1, "ACTIVE")));
            Set<String> consumed = new HashSet<>();
            when(repository.consumeNonce(anyString(), anyString(), anyString(), any(), any())).thenAnswer(invocation ->
                    consumed.add(invocation.getArgument(0) + ":" + invocation.getArgument(1) + ":" + invocation.getArgument(2)));
            service = new AuthAuthorizationRpcServiceImpl(tokens, repository, properties, clock);
        }

        void register(String kid, String service, KeyPair pair, Set<String> audiences) {
            var caller = new AuthAuthorizationProperties.TrustedCaller();
            caller.setService(service);
            caller.setPublicKey(ServiceIdentityCrypto.encodePublicKey(pair.getPublic()));
            caller.setOperations(service.equals("agent-service")
                    ? Set.of("RESOLVE_REQUEST_AUTHORIZATION", "VALIDATE_DELEGATION", "REFRESH_AUTHORIZATION")
                    : Set.of("RESOLVE_REQUEST_AUTHORIZATION", "VALIDATE_DELEGATION"));
            caller.setAudiences(audiences);
            caller.setPermissionCodes(Set.copyOf(PERMISSIONS));
            properties.getTrustedCallers().put(kid, caller);
        }

        CallerProof entryProof() {
            return AuthzSignature.sign(new CallerProof("gateway", "auth-service", AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION,
                    request.requestId(), AuthzCanonicalHash.resolveRequestHash(TOKEN, request), request.nonce(), now,
                    now + 60, AuthzContract.ALGORITHM, "gateway-key", null), gateway.getPrivate());
        }

        AuthzResponse entry() {
            return service.resolveRequestAuthorization(TOKEN, request, entryProof());
        }

        void version(long revision, long epoch, List<String> permissions, Long deadline, boolean admin) {
            doReturn(Optional.of(new EffectivePermissionService.Snapshot(
                    7, revision, epoch, "ACTIVE", admin, 1, permissions,
                    deadline == null ? null : Instant.ofEpochSecond(deadline)))).when(repository).snapshot(eq(7L), any());
            doReturn(Optional.of(new AuthorizationRepository.UserVersion(7, revision, epoch, "ACTIVE")))
                    .when(repository).currentVersion(7);
        }

        RequestBinding refreshBinding(AuthorizationContext original, int id) {
            long issuedAt = clock.instant().getEpochSecond();
            return new RequestBinding("agent-service", "agent-service", "POST", "/authz/refresh", "/authz/refresh",
                    List.of(), "refresh-" + id, original.runId(), original.sessionId(), HASH, original.policyDigest(),
                    nonce(id), issuedAt, Math.min(issuedAt + 60, original.expiresAtEpochSecond()));
        }

        CallerProof refreshProof(String token, AuthorizationContext original, RequestBinding binding) {
            return AuthzSignature.sign(new CallerProof(binding.callerService(), "auth-service", AuthzRpcOperation.REFRESH_AUTHORIZATION,
                    binding.requestId(), AuthzCanonicalHash.refreshRequestHash(token, original, binding), binding.nonce(),
                    binding.issuedAtEpochSecond(), binding.expiresAtEpochSecond(), AuthzContract.ALGORITHM, "agent-key", null), agent.getPrivate());
        }

        AuthzResponse refresh(AuthorizationContext original, int id) {
            RequestBinding binding = refreshBinding(original, id);
            return service.refreshAuthorization(TOKEN, original, binding, refreshProof(TOKEN, original, binding));
        }

        AuthzResponse delegate(AuthorizationContext parent, List<String> operations, int id) {
            long issuedAt = clock.instant().getEpochSecond();
            var binding = new DelegationBinding("agent-service", "interview-service", parent.actorId(), operations,
                    parent.runId(), "child-" + id, "delegation-" + id, HASH, parent.policyDigest(), nonce(id),
                    issuedAt, Math.min(issuedAt + 60, parent.expiresAtEpochSecond()));
            String hash = AuthzCanonicalHash.delegationRequestHash(parent, binding);
            var source = AuthzSignature.sign(new CallerProof("agent-service", "interview-service", AuthzRpcOperation.VALIDATE_DELEGATION,
                    binding.requestId(), hash, binding.nonce(), issuedAt, binding.expiresAtEpochSecond(),
                    AuthzContract.ALGORITHM, "agent-key", null), agent.getPrivate());
            var target = AuthzSignature.sign(new CallerProof("interview-service", "auth-service", AuthzRpcOperation.VALIDATE_DELEGATION,
                    binding.requestId(), hash, nonce(id + 100), issuedAt, binding.expiresAtEpochSecond(),
                    AuthzContract.ALGORITHM, "interview-key", null), interview.getPrivate());
            return service.validateDelegation(parent, binding, source, target);
        }

        AuthorizationSession session(AuthzResponse entry) {
            return AuthorizationSession.fromEntry(entry, entry.context().audience(), correlation(entry),
                    new AuthorizationCache(8, 100_000, Duration.ofMinutes(5), 8), authorityKeys(), clock);
        }

        Map<String, java.security.PublicKey> authorityKeys() {
            return Map.of("auth-key", authority.getPublic());
        }
    }
}
