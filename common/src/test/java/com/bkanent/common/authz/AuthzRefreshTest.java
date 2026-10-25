package com.bkanent.common.authz;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bkanent.common.authz.AuthzTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthzRefreshTest {
    @Test
    void refreshHashHasIndependentTypeAndCoversCredentialCompleteSignedOriginalAndRequest() throws Exception {
        KeyPair keys = keyPair();
        AuthorizationContext original = AuthzSignature.sign(context(snapshot()), keys.getPrivate());
        RequestBinding binding = refreshBinding(original, "refresh-1", nonce(1));
        String hash = AuthzCanonicalHash.refreshRequestHash("synthetic-access", original, binding);
        assertThat(hash).hasSize(64).isNotEqualTo(AuthzCanonicalHash.resolveRequestHash("synthetic-access", binding));
        assertThat(hash).isNotEqualTo(AuthzCanonicalHash.refreshRequestHash("another-access", original, binding));
        AuthorizationContext changedSignature = copy(original, original.keyId(), invalidSignature());
        AuthorizationContext changedKey = copy(original, "rotated-key", original.signature());
        assertThat(hash).isNotEqualTo(AuthzCanonicalHash.refreshRequestHash("synthetic-access", changedSignature, binding));
        assertThat(hash).isNotEqualTo(AuthzCanonicalHash.refreshRequestHash("synthetic-access", changedKey, binding));
        AuthorizationContext changedScope = AuthzSignature.sign(new AuthorizationContext(original.actorId(),
                original.permissionRevision(), original.credentialEpoch(), original.permissionHash(), List.of("memory.read"),
                original.audience(), original.requestId(), original.runId(), original.sessionId(), original.policyDigest(),
                original.requestHash(), original.nonce(), original.issuedAtEpochSecond(), original.expiresAtEpochSecond(),
                original.runHardDeadlineEpochSecond(), original.algorithm(), original.keyId(), null), keys.getPrivate());
        assertThat(hash).isNotEqualTo(AuthzCanonicalHash.refreshRequestHash("synthetic-access", changedScope, binding));
        RequestBinding changedBody = new RequestBinding(binding.callerService(), binding.audience(), binding.method(),
                binding.originalPath(), binding.targetPath(), binding.query(), binding.requestId(), binding.runId(),
                binding.sessionId(), "b".repeat(64), binding.policyDigest(), binding.nonce(), binding.issuedAtEpochSecond(),
                binding.expiresAtEpochSecond());
        assertThat(hash).isNotEqualTo(AuthzCanonicalHash.refreshRequestHash("synthetic-access", original, changedBody));
        assertThat(hash).isNotEqualTo(AuthzCanonicalHash.refreshRequestHash("synthetic-access", original,
                refreshBinding(original, "refresh-2", nonce(2))));
        assertThatThrownBy(() -> AuthzCanonicalHash.refreshRequestHash("synthetic-access", context(snapshot()), binding))
                .isInstanceOf(AuthzException.class).hasMessage("INVALID_ARGUMENT");
    }

    @Test
    void refreshProofCannotUseResolveOrDelegateOperationEvenWithMatchingHashAndRegisteredKey() throws Exception {
        KeyPair authority = keyPair();
        KeyPair caller = keyPair();
        AuthorizationContext original = AuthzSignature.sign(context(snapshot()), authority.getPrivate());
        RequestBinding binding = refreshBinding(original, "refresh-1", nonce(1));
        String hash = AuthzCanonicalHash.refreshRequestHash("synthetic-access", original, binding);
        for (AuthzRpcOperation operation : AuthzRpcOperation.values()) {
            CallerProof proof = AuthzSignature.sign(new CallerProof("agent-service", "auth-service", operation,
                    binding.requestId(), hash, binding.nonce(), NOW + 10, NOW + 60,
                    AuthzContract.ALGORITHM, "caller-key", null), caller.getPrivate());
            if (operation == AuthzRpcOperation.REFRESH_AUTHORIZATION) {
                AuthzSignature.verifyCallerProof(proof, "agent-service", "auth-service", operation, binding.requestId(),
                        hash, binding.nonce(), Set.of(operation), Map.of("caller-key", caller.getPublic()), Instant.ofEpochSecond(NOW + 10));
            } else {
                assertThatThrownBy(() -> AuthzSignature.verifyCallerProof(proof, "agent-service", "auth-service",
                        AuthzRpcOperation.REFRESH_AUTHORIZATION, binding.requestId(), hash, binding.nonce(),
                        Set.of(AuthzRpcOperation.values()), Map.of("caller-key", caller.getPublic()), Instant.ofEpochSecond(NOW + 10)))
                        .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
            }
        }
    }

    @Test
    void refreshBindingRetainsAudienceIdentityAndCannotUseOldRequestOrExtendDeadline() throws Exception {
        KeyPair keys = keyPair();
        AuthorizationContext original = AuthzSignature.sign(context(snapshot()), keys.getPrivate());
        Map<String, java.security.PublicKey> registry = Map.of(KID, keys.getPublic());
        RequestBinding binding = refreshBinding(original, "refresh-1", nonce(1));
        AuthzSignature.verifyRefreshBinding(original, binding, registry, Instant.ofEpochSecond(NOW + 10));
        RequestBinding sameRequest = refreshBinding(original, original.requestId(), nonce(1));
        RequestBinding sameNonce = refreshBinding(original, "refresh-1", original.nonce());
        for (RequestBinding invalid : List.of(sameRequest, sameNonce)) {
            assertThatThrownBy(() -> AuthzSignature.verifyRefreshBinding(original, invalid, registry, Instant.ofEpochSecond(NOW + 10)))
                    .isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
        }
        RequestBinding extendsOriginal = new RequestBinding("agent-service", "agent-service", "POST", "/authz/refresh",
                "/authz/refresh", List.of(), "refresh-late", original.runId(), original.sessionId(), HASH,
                original.policyDigest(), nonce(2), NOW + 290, NOW + 350);
        assertThatThrownBy(() -> AuthzSignature.verifyRefreshBinding(original, extendsOriginal, registry, Instant.ofEpochSecond(NOW + 290)))
                .isInstanceOf(AuthzException.class).hasMessage("CONTEXT_CONFLICT");
        assertThatThrownBy(() -> AuthzSignature.verifyRefreshBinding(original, binding, registry, Instant.ofEpochSecond(NOW + 300)))
                .isInstanceOf(AuthzException.class).hasMessage("EXPIRED");
        assertThatThrownBy(() -> AuthzSignature.verifyRefreshBinding(copy(original, KID, invalidSignature()), binding, registry,
                Instant.ofEpochSecond(NOW + 10))).isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
    }

    @Test
    void inactiveFreshnessIsVerifiableEvidenceButNeverUserAuthorization() throws Exception {
        KeyPair keys = keyPair();
        Map<String, java.security.PublicKey> registry = Map.of(KID, keys.getPublic());
        for (AuthorizationStatus status : AuthorizationStatus.values()) {
            AuthzFreshness proof = AuthzSignature.sign(new AuthzFreshness("123", 7, 0, status, HASH,
                    NOW, NOW + 30, NONCE, AuthzContract.ALGORITHM, KID, null), keys.getPrivate());
            AuthzSignature.verifyFreshnessAttestation(proof, registry, Instant.ofEpochSecond(NOW + 10));
            if (status != AuthorizationStatus.ACTIVE) {
                assertThatThrownBy(() -> AuthzSignature.verifyFreshness(proof, registry, Instant.ofEpochSecond(NOW + 10)))
                        .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
            }
            assertThatThrownBy(() -> AuthzSignature.verifyFreshnessAttestation(proof, registry, Instant.ofEpochSecond(NOW + 30)))
                    .isInstanceOf(AuthzException.class).hasMessage("EXPIRED");
            assertThatThrownBy(() -> AuthzSignature.verifyFreshnessAttestation(proof, Map.of(), Instant.ofEpochSecond(NOW + 10)))
                    .isInstanceOf(AuthzException.class).hasMessage("AUTHENTICATION_FAILED");
        }
    }

    private static RequestBinding refreshBinding(AuthorizationContext original, String requestId, String nonce) {
        return new RequestBinding("agent-service", "agent-service", "POST", "/authz/refresh", "/authz/refresh", List.of(),
                requestId, original.runId(), original.sessionId(), HASH, original.policyDigest(), nonce, NOW + 10, NOW + 60);
    }

    private static AuthorizationContext copy(AuthorizationContext original, String keyId, String signature) {
        return new AuthorizationContext(original.actorId(), original.permissionRevision(), original.credentialEpoch(),
                original.permissionHash(), original.allowedOperations(), original.audience(), original.requestId(),
                original.runId(), original.sessionId(), original.policyDigest(), original.requestHash(), original.nonce(),
                original.issuedAtEpochSecond(), original.expiresAtEpochSecond(), original.runHardDeadlineEpochSecond(),
                original.algorithm(), keyId, signature);
    }

    private static String invalidSignature() {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]);
    }

    private static String nonce(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
