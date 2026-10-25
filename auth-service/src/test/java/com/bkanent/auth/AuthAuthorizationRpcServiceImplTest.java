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
import java.time.Instant;
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
    private static final List<String> PERMISSIONS = List.of("agent.chat.use", "interview.read");

    @Test
    void entryAuthenticatesOnceAndReturnsRequestBoundSignedAuthority() throws Exception {
        Fixture f = new Fixture();
        var response = f.service.resolveRequestAuthorization(TOKEN, f.request, f.entryProof());
        AuthzSignature.verifyResponse(response, "agent-service", f.request.requestId(),
                AuthzCanonicalHash.resolveRequestHash(TOKEN, f.request), f.request.nonce(), f.authorityKeys(), Instant.now());
        assertThat(response.snapshot().permissions()).containsExactlyElementsOf(PERMISSIONS);
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
        AuthzSignature.verifyChildContext(entry.context(), child.context(), binding, f.authorityKeys(), Instant.now());
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

    private static String nonce(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class Fixture {
        final long now = Instant.now().getEpochSecond();
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
            register("agent-key", "agent-service", agent, Set.of("interview-service"));
            register("interview-key", "interview-service", interview, Set.of("interview-service"));
            when(tokens.parse(TOKEN)).thenReturn(new AuthTokenService.TokenPrincipal(7, "access", now + 600));
            when(repository.snapshot(eq(7L), any())).thenReturn(Optional.of(new EffectivePermissionService.Snapshot(
                    7, 1, 1, "ACTIVE", false, 1, PERMISSIONS, null)));
            when(repository.currentVersion(7)).thenReturn(Optional.of(new AuthorizationRepository.UserVersion(7, 1, 1, "ACTIVE")));
            when(repository.consumeNonce(anyString(), anyString(), anyString(), any(), any())).thenReturn(true);
            service = new AuthAuthorizationRpcServiceImpl(tokens, repository, properties);
        }

        void register(String kid, String service, KeyPair pair, Set<String> audiences) {
            var caller = new AuthAuthorizationProperties.TrustedCaller();
            caller.setService(service);
            caller.setPublicKey(ServiceIdentityCrypto.encodePublicKey(pair.getPublic()));
            caller.setOperations(Set.of("RESOLVE_REQUEST_AUTHORIZATION", "VALIDATE_DELEGATION"));
            caller.setAudiences(audiences);
            caller.setPermissionCodes(Set.copyOf(PERMISSIONS));
            properties.getTrustedCallers().put(kid, caller);
        }

        CallerProof entryProof() {
            return AuthzSignature.sign(new CallerProof("gateway", "auth-service", AuthzRpcOperation.RESOLVE_REQUEST_AUTHORIZATION,
                    request.requestId(), AuthzCanonicalHash.resolveRequestHash(TOKEN, request), request.nonce(), now,
                    now + 60, AuthzContract.ALGORITHM, "gateway-key", null), gateway.getPrivate());
        }

        Map<String, java.security.PublicKey> authorityKeys() {
            return Map.of("auth-key", authority.getPublic());
        }
    }
}
