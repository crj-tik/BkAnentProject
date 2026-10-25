package com.bkanent.common.authz;

import com.bkanent.common.security.ServiceIdentityCrypto;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bkanent.common.authz.AuthzTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthzSignatureTest {
    @Test
    void allFourSignedTypesAndCorrelatedResponseVerify() throws Exception {
        KeyPair authority = keyPair();
        Map<String, java.security.PublicKey> keys = Map.of(KID, authority.getPublic());
        AuthorizationSnapshot snapshot = AuthzSignature.sign(snapshot(), authority.getPrivate());
        AuthorizationContext context = AuthzSignature.sign(context(snapshot), authority.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(freshness(snapshot), authority.getPrivate());
        AuthzResponse response = new AuthzResponse(snapshot, context, freshness);
        AuthzSignature.verifyResponse(response, "agent-service", context.requestId(), HASH, NONCE, keys, Instant.ofEpochSecond(NOW));

        KeyPair caller = keyPair();
        DelegationBinding binding = binding();
        CallerProof proof = AuthzSignature.sign(caller(binding, context), caller.getPrivate());
        verifyCaller(proof, Map.of(proof.keyId(), caller.getPublic()), NOW);
        AuthzSignature.verifyDelegationBinding(context, binding, binding, keys, Instant.ofEpochSecond(NOW));
        assertEquals(NOW + 30, AuthzDeadlines.effectiveDeadline(context, snapshot, freshness));
    }

    @Test
    void rejectsUnknownAndWrongKeyIdsAndPublicKeys() throws Exception {
        KeyPair key = keyPair();
        AuthorizationSnapshot signed = AuthzSignature.sign(snapshot(), key.getPrivate());
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifySnapshot(signed, Map.of(), at(NOW)));
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifySnapshot(signed,
                Map.of("another-kid", key.getPublic()), at(NOW)));
        KeyPair wrong = keyPair();
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifySnapshot(signed,
                Map.of(KID, wrong.getPublic()), at(NOW)));
        AuthorizationSnapshot relabeled = new AuthorizationSnapshot(signed.actorId(), signed.permissionRevision(),
                signed.catalogVersion(), signed.businessAdmin(), signed.permissions(), null, signed.algorithm(),
                "relabelled", signed.signature());
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifySnapshot(relabeled,
                Map.of("relabelled", key.getPublic()), at(NOW)));
    }

    @Test
    void rejectsCrossProtocolAndCrossTypeSignaturesAndAlgorithmDowngrade() throws Exception {
        KeyPair key = keyPair();
        AuthorizationSnapshot source = snapshot();
        String wrongDomain = ServiceIdentityCrypto.encodeSignature(ServiceIdentityCrypto.sign(
                "BK-ANENT:A2A-AUTH:v1", AuthzContract.ALGORITHM,
                AuthzCanonicalHash.snapshotBytes(source), key.getPrivate()));
        AuthorizationSnapshot foreign = new AuthorizationSnapshot(source.actorId(), source.permissionRevision(),
                source.catalogVersion(), source.businessAdmin(), source.permissions(), null, source.algorithm(), KID, wrongDomain);
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifySnapshot(foreign, Map.of(KID, key.getPublic()), at(NOW)));
        AuthzFreshness freshness = freshness(source);
        String wrongType = ServiceIdentityCrypto.encodeSignature(ServiceIdentityCrypto.sign(
                AuthzSignature.SIGNATURE_DOMAIN, AuthzContract.ALGORITHM,
                AuthzCanonicalHash.snapshotBytes(source), key.getPrivate()));
        AuthzFreshness confused = new AuthzFreshness(freshness.userId(), freshness.permissionRevision(), 0,
                freshness.status(), freshness.permissionHash(), NOW, NOW + 30, NONCE,
                AuthzContract.ALGORITHM, KID, wrongType);
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifyFreshness(confused, Map.of(KID, key.getPublic()), at(NOW)));
        rejects(AuthzErrorCode.INVALID_ARGUMENT, () -> new AuthorizationSnapshot("123", 7, 3, false,
                PERMISSIONS, null, "HS256", KID, null));
    }

    @Test
    void expiresExactlyAtDeadlineAndRejectsFutureIssuedAndInactiveAuthority() throws Exception {
        KeyPair key = keyPair();
        var keys = Map.of(KID, key.getPublic());
        AuthorizationContext context = AuthzSignature.sign(context(snapshot()), key.getPrivate());
        rejects(AuthzErrorCode.EXPIRED, () -> AuthzSignature.verifyContext(context, "agent-service", keys, at(NOW + 300)));
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifyContext(context, "agent-service", keys, at(NOW - 1)));
        AuthzFreshness fresh = AuthzSignature.sign(freshness(snapshot()), key.getPrivate());
        rejects(AuthzErrorCode.EXPIRED, () -> AuthzSignature.verifyFreshness(fresh, keys, at(NOW + 30)));
        AuthzFreshness disabled = AuthzSignature.sign(new AuthzFreshness("123", 7, 0, AuthorizationStatus.DISABLED,
                fresh.permissionHash(), NOW, NOW + 30, NONCE, AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifyFreshness(disabled, keys, at(NOW)));
        AuthorizationSnapshot expiring = AuthzSignature.sign(new AuthorizationSnapshot("123", 7, 3, false,
                PERMISSIONS, NOW + 10, AuthzContract.ALGORITHM, KID, null), key.getPrivate());
        rejects(AuthzErrorCode.EXPIRED, () -> AuthzSignature.verifySnapshot(expiring, keys, at(NOW + 10)));
    }

    @Test
    void bodyTaskTargetPolicyAndNonceTamperingInvalidateRequestProof() throws Exception {
        KeyPair authority = keyPair();
        KeyPair caller = keyPair();
        AuthorizationContext parent = AuthzSignature.sign(context(snapshot()), authority.getPrivate());
        DelegationBinding original = binding();
        CallerProof proof = AuthzSignature.sign(caller(original, parent), caller.getPrivate());
        for (DelegationBinding changed : List.of(
                changed(original, "memory-service", "task-1", AuthzCanonicalHash.bodyHash(new byte[]{1}), HASH, NONCE),
                changed(original, "memory-service", "task-2", HASH, HASH, NONCE),
                changed(original, "listing-service", "task-1", HASH, HASH, NONCE),
                changed(original, "memory-service", "task-1", HASH, "a".repeat(64), NONCE),
                changed(original, "memory-service", "task-1", HASH, HASH,
                        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]).replaceFirst("A", "B")))) {
            rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifyCallerProof(proof,
                    "agent-service", "auth-service", AuthzRpcOperation.VALIDATE_DELEGATION,
                    changed.requestId(), AuthzCanonicalHash.delegationRequestHash(parent, changed), changed.nonce(),
                    Set.of(AuthzRpcOperation.VALIDATE_DELEGATION), Map.of(proof.keyId(), caller.getPublic()), at(NOW)));
            rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifyDelegationBinding(parent,
                    original, changed, Map.of(KID, authority.getPublic()), at(NOW)));
        }
    }

    @Test
    void parentSignedScopeAndFixedDeadlineCannotExpand() throws Exception {
        KeyPair authority = keyPair();
        var keys = Map.of(KID, authority.getPublic());
        AuthorizationContext base = context(snapshot());
        AuthorizationContext parent = AuthzSignature.sign(new AuthorizationContext(base.actorId(), 7, 0,
                base.permissionHash(), List.of("memory.read"), "agent-service", base.requestId(), base.runId(),
                base.sessionId(), HASH, HASH, NONCE, NOW, NOW + 50, NOW + 300,
                AuthzContract.ALGORITHM, KID, null), authority.getPrivate());
        DelegationBinding extended = binding();
        rejects(AuthzErrorCode.CONTEXT_CONFLICT, () -> AuthzSignature.verifyDelegationBinding(parent, extended,
                extended, keys, at(NOW)));
        DelegationBinding expanded = new DelegationBinding("agent-service", "memory-service", "123",
                List.of("interview.read"), "run-1", "task-1", "child-request", HASH, HASH, NONCE, NOW, NOW + 40);
        rejects(AuthzErrorCode.PERMISSION_DENIED, () -> AuthzSignature.verifyDelegationBinding(parent, expanded, expanded, keys, at(NOW)));
        DelegationBinding valid = new DelegationBinding("agent-service", "memory-service", "123",
                List.of("memory.read"), "run-1", "task-1", "child-request", HASH, HASH, NONCE, NOW, NOW + 40);
        AuthorizationContext child = AuthzSignature.sign(new AuthorizationContext("123", 7, 0, parent.permissionHash(),
                valid.operations(), "memory-service", valid.requestId(), "run-1", "session-1", HASH,
                AuthzCanonicalHash.delegationRequestHash(parent, valid), NONCE, NOW, NOW + 50, NOW + 300,
                AuthzContract.ALGORITHM, KID, null), authority.getPrivate());
        AuthzSignature.verifyChildContext(parent, child, valid, keys, at(NOW));
        // Same cryptographic proof is locally verifiable twice: persistent replay consumption is Auth's job.
        AuthzSignature.verifyChildContext(parent, child, valid, keys, at(NOW));
    }

    @Test
    void mismatchedResponseAndCallerAclAreRejected() throws Exception {
        KeyPair key = keyPair();
        AuthorizationSnapshot snapshot = AuthzSignature.sign(snapshot(), key.getPrivate());
        AuthorizationContext context = AuthzSignature.sign(context(snapshot), key.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(freshness(snapshot), key.getPrivate());
        AuthzResponse response = new AuthzResponse(snapshot, context, freshness);
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifyResponse(response, "agent-service",
                "other-request", HASH, NONCE, Map.of(KID, key.getPublic()), at(NOW)));
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifyResponse(response, "memory-service",
                context.requestId(), HASH, NONCE, Map.of(KID, key.getPublic()), at(NOW)));
        AuthzFreshness other = new AuthzFreshness("124", 7, 0, AuthorizationStatus.ACTIVE,
                context.permissionHash(), NOW, NOW + 30, NONCE, AuthzContract.ALGORITHM, KID, null);
        rejects(AuthzErrorCode.CONTEXT_CONFLICT, () -> new AuthzResponse(snapshot, context, other));
        CallerProof proof = AuthzSignature.sign(caller(binding(), context), key.getPrivate());
        rejects(AuthzErrorCode.PERMISSION_DENIED, () -> AuthzSignature.verifyCallerProof(proof,
                proof.callerService(), proof.audience(), proof.operation(), proof.requestId(), proof.requestHash(), NONCE,
                Set.of(), Map.of(proof.keyId(), key.getPublic()), at(NOW)));
    }

    @Test
    void sourceAndReceiverProofsAreIndependentAndCannotSubstituteForEachOther() throws Exception {
        var authority = keyPair();
        var source = keyPair();
        var receiver = keyPair();
        var parent = AuthzSignature.sign(context(snapshot()), authority.getPrivate());
        var binding = binding();
        String requestHash = AuthzCanonicalHash.delegationRequestHash(parent, binding);
        byte[] receiverNonceBytes = new byte[32];
        receiverNonceBytes[0] = 1;
        String receiverNonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(receiverNonceBytes);
        CallerProof originProof = AuthzSignature.sign(new CallerProof(binding.callerService(), binding.audience(),
                AuthzRpcOperation.VALIDATE_DELEGATION, binding.requestId(), requestHash, binding.nonce(), NOW,
                NOW + 60, AuthzContract.ALGORITHM, "origin-key", null), source.getPrivate());
        CallerProof receiverProof = AuthzSignature.sign(new CallerProof(binding.audience(), "auth-service",
                AuthzRpcOperation.VALIDATE_DELEGATION, binding.requestId(), requestHash, receiverNonce, NOW,
                NOW + 60, AuthzContract.ALGORITHM, "receiver-key", null), receiver.getPrivate());
        var acl = Set.of(AuthzRpcOperation.VALIDATE_DELEGATION);
        var sourceKeys = Map.of("origin-key", source.getPublic());
        var receiverKeys = Map.of("receiver-key", receiver.getPublic());
        AuthzSignature.verifyCallerProof(originProof, binding.callerService(), binding.audience(), originProof.operation(),
                binding.requestId(), requestHash, binding.nonce(), acl, sourceKeys, at(NOW));
        AuthzSignature.verifyCallerProof(receiverProof, binding.audience(), "auth-service", receiverProof.operation(),
                binding.requestId(), requestHash, receiverNonce, acl, receiverKeys, at(NOW));
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifyCallerProof(receiverProof,
                binding.callerService(), binding.audience(), receiverProof.operation(), binding.requestId(), requestHash,
                binding.nonce(), acl, sourceKeys, at(NOW)));
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifyCallerProof(originProof,
                binding.audience(), "auth-service", originProof.operation(), binding.requestId(), requestHash,
                receiverNonce, acl, receiverKeys, at(NOW)));
    }

    @Test
    void contextScopeDeadlineAndSnapshotContentAreCryptographicallyBound() throws Exception {
        KeyPair key = keyPair();
        var keys = Map.of(KID, key.getPublic());
        AuthorizationContext signed = AuthzSignature.sign(context(snapshot()), key.getPrivate());
        AuthorizationContext alteredDeadline = new AuthorizationContext(signed.actorId(), 7, 0, signed.permissionHash(),
                signed.allowedOperations(), signed.audience(), signed.requestId(), signed.runId(), signed.sessionId(),
                HASH, HASH, NONCE, NOW, NOW + 299, NOW + 299, signed.algorithm(), KID, signed.signature());
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifyContext(alteredDeadline,
                "agent-service", keys, at(NOW)));
        AuthorizationContext alteredScope = new AuthorizationContext(signed.actorId(), 7, 0, signed.permissionHash(),
                List.of("auth.users.manage"), signed.audience(), signed.requestId(), signed.runId(), signed.sessionId(),
                HASH, HASH, NONCE, NOW, NOW + 300, NOW + 300, signed.algorithm(), KID, signed.signature());
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifyContext(alteredScope,
                "agent-service", keys, at(NOW)));
        AuthorizationSnapshot stable = AuthzSignature.sign(snapshot(), key.getPrivate());
        AuthorizationSnapshot changed = new AuthorizationSnapshot("123", 7, 3, true, PERMISSIONS, null,
                AuthzContract.ALGORITHM, KID, stable.signature());
        rejects(AuthzErrorCode.AUTHENTICATION_FAILED, () -> AuthzSignature.verifySnapshot(changed, keys, at(NOW)));
    }

    private static DelegationBinding changed(DelegationBinding source, String audience, String task, String body,
                                              String policy, String nonce) {
        return new DelegationBinding(source.callerService(), audience, source.actorId(), source.operations(), source.runId(),
                task, source.requestId(), body, policy, nonce, source.issuedAtEpochSecond(), source.expiresAtEpochSecond());
    }

    private static Instant at(long seconds) { return Instant.ofEpochSecond(seconds); }

    private static void verifyCaller(CallerProof proof, Map<String, java.security.PublicKey> keys, long now) {
        AuthzSignature.verifyCallerProof(proof, proof.callerService(), proof.audience(), proof.operation(), proof.requestId(),
                proof.requestHash(), NONCE, Set.of(AuthzRpcOperation.VALIDATE_DELEGATION), keys, at(now));
    }

    private static void rejects(AuthzErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(AuthzException.class, action).code());
    }
}
