package com.bkanent.common.authz;

import com.bkanent.common.security.ServiceIdentityCrypto;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Ed25519-only primitives, isolated from existing A2A/Memory/RAG signatures (LR-98). */
public final class AuthzSignature {
    public static final String SIGNATURE_DOMAIN = "bkanent-authz-v1";

    private AuthzSignature() { }

    public static AuthorizationSnapshot sign(AuthorizationSnapshot value, PrivateKey key) {
        AuthzContract.require(value != null);
        return new AuthorizationSnapshot(value.actorId(), value.permissionRevision(), value.catalogVersion(),
                value.businessAdmin(), value.permissions(), value.grantDeadlineEpochSecond(), value.algorithm(),
                value.keyId(), signBytes(AuthzCanonicalHash.snapshotBytes(value), key));
    }

    public static AuthzFreshness sign(AuthzFreshness value, PrivateKey key) {
        AuthzContract.require(value != null);
        return new AuthzFreshness(value.userId(), value.permissionRevision(), value.credentialEpoch(), value.status(),
                value.permissionHash(), value.checkedAtEpochSecond(), value.notAfterEpochSecond(), value.nonce(),
                value.algorithm(), value.keyId(), signBytes(AuthzCanonicalHash.freshnessBytes(value), key));
    }

    public static AuthorizationContext sign(AuthorizationContext value, PrivateKey key) {
        AuthzContract.require(value != null);
        return new AuthorizationContext(value.actorId(), value.permissionRevision(), value.credentialEpoch(),
                value.permissionHash(), value.allowedOperations(), value.audience(), value.requestId(), value.runId(),
                value.sessionId(), value.policyDigest(), value.requestHash(), value.nonce(), value.issuedAtEpochSecond(),
                value.expiresAtEpochSecond(), value.runHardDeadlineEpochSecond(), value.algorithm(), value.keyId(),
                signBytes(AuthzCanonicalHash.contextBytes(value), key));
    }

    public static CallerProof sign(CallerProof value, PrivateKey key) {
        AuthzContract.require(value != null);
        return new CallerProof(value.callerService(), value.audience(), value.operation(), value.requestId(),
                value.requestHash(), value.nonce(), value.issuedAtEpochSecond(), value.expiresAtEpochSecond(),
                value.algorithm(), value.keyId(), signBytes(AuthzCanonicalHash.callerBytes(value), key));
    }

    /** Snapshot validity has only a stable grant deadline, never sliding cache/issuance timing. */
    public static void verifySnapshot(AuthorizationSnapshot value, Map<String, PublicKey> keys, Instant now) {
        authenticate(value != null && now != null);
        verifyBytes(AuthzCanonicalHash.snapshotBytes(value), value.algorithm(), value.keyId(), value.signature(), keys);
        AuthzContract.check(value.grantDeadlineEpochSecond() == null
                || now.getEpochSecond() < value.grantDeadlineEpochSecond(), AuthzErrorCode.EXPIRED);
    }

    public static void verifyFreshness(AuthzFreshness value, Map<String, PublicKey> keys, Instant now) {
        authenticate(value != null);
        verifyBytes(AuthzCanonicalHash.freshnessBytes(value), value.algorithm(), value.keyId(), value.signature(), keys);
        time(value.checkedAtEpochSecond(), value.notAfterEpochSecond(), now);
        authenticate(value.status() == AuthorizationStatus.ACTIVE);
    }

    public static void verifyContext(AuthorizationContext value, String expectedAudience,
                                     Map<String, PublicKey> keys, Instant now) {
        authenticate(value != null && Objects.equals(expectedAudience, value.audience()));
        verifyBytes(AuthzCanonicalHash.contextBytes(value), value.algorithm(), value.keyId(), value.signature(), keys);
        time(value.issuedAtEpochSecond(), value.expiresAtEpochSecond(), now);
        AuthzContract.check(now.getEpochSecond() < value.runHardDeadlineEpochSecond(), AuthzErrorCode.EXPIRED);
    }

    /** A valid signature alone is not sufficient: responses must match the initiating RPC. */
    public static void verifyResponse(AuthzResponse response, String expectedAudience, String expectedRequestId,
                                      String expectedRequestHash, String expectedNonce,
                                      Map<String, PublicKey> authorityKeys, Instant now) {
        authenticate(response != null);
        // Recheck correlation instead of relying on a transport's constructor behavior.
        new AuthzResponse(response.snapshot(), response.context(), response.freshness());
        verifySnapshot(response.snapshot(), authorityKeys, now);
        verifyFreshness(response.freshness(), authorityKeys, now);
        verifyContext(response.context(), expectedAudience, authorityKeys, now);
        AuthorizationContext context = response.context();
        authenticate(Objects.equals(context.requestId(), expectedRequestId)
                && Objects.equals(context.requestHash(), expectedRequestHash)
                && Objects.equals(context.nonce(), expectedNonce));
    }

    /**
     * Key maps must be scoped to this exact caller/authority by the provider. Key registration does
     * not grant operation ACLs. Common validates nonce form/binding only; Auth consumes it durably.
     */
    public static void verifyCallerProof(CallerProof value, String expectedCaller, String expectedAudience,
                                         AuthzRpcOperation expectedOperation, String expectedRequestId,
                                         String expectedRequestHash, String expectedNonce,
                                         Set<AuthzRpcOperation> callerAcl, Map<String, PublicKey> callerKeys,
                                         Instant now) {
        authenticate(value != null && Objects.equals(value.callerService(), expectedCaller)
                && Objects.equals(value.audience(), expectedAudience) && value.operation() == expectedOperation
                && Objects.equals(value.requestId(), expectedRequestId)
                && Objects.equals(value.requestHash(), expectedRequestHash)
                && Objects.equals(value.nonce(), expectedNonce));
        verifyBytes(AuthzCanonicalHash.callerBytes(value), value.algorithm(), value.keyId(), value.signature(), callerKeys);
        time(value.issuedAtEpochSecond(), value.expiresAtEpochSecond(), now);
        AuthzContract.check(callerAcl != null && callerAcl.contains(value.operation()), AuthzErrorCode.PERMISSION_DENIED);
    }

    /** Local precondition only, not the full 4.2 authoritative RPC/replay implementation. */
    public static void verifyDelegationBinding(AuthorizationContext parent, DelegationBinding binding,
                                               DelegationBinding actualRequest,
                                               Map<String, PublicKey> authorityKeys, Instant now) {
        authenticate(parent != null && binding != null && actualRequest != null);
        verifyContext(parent, binding.callerService(), authorityKeys, now);
        authenticate(binding.equals(actualRequest));
        time(binding.issuedAtEpochSecond(), binding.expiresAtEpochSecond(), now);
        AuthzContract.check(parent.actorId().equals(binding.actorId())
                && Objects.equals(parent.runId(), binding.runId())
                && parent.policyDigest().equals(binding.policyDigest()), AuthzErrorCode.CONTEXT_CONFLICT);
        AuthzContract.check(parent.allowedOperations().containsAll(binding.operations()), AuthzErrorCode.PERMISSION_DENIED);
        AuthzContract.check(binding.issuedAtEpochSecond() >= parent.issuedAtEpochSecond()
                && binding.expiresAtEpochSecond() <= parent.expiresAtEpochSecond()
                && binding.expiresAtEpochSecond() <= parent.runHardDeadlineEpochSecond(), AuthzErrorCode.CONTEXT_CONFLICT);
    }

    /** Validated parent facts bound the child; no caller-supplied unsigned permission scope. */
    public static void verifyChildContext(AuthorizationContext parent, AuthorizationContext child,
                                          DelegationBinding binding, Map<String, PublicKey> authorityKeys, Instant now) {
        verifyDelegationBinding(parent, binding, binding, authorityKeys, now);
        verifyContext(child, binding.audience(), authorityKeys, now);
        AuthzContract.check(parent.actorId().equals(child.actorId())
                && parent.permissionRevision() == child.permissionRevision()
                && parent.credentialEpoch() == child.credentialEpoch()
                && parent.permissionHash().equals(child.permissionHash())
                && Objects.equals(parent.runId(), child.runId())
                && Objects.equals(parent.sessionId(), child.sessionId())
                && parent.policyDigest().equals(child.policyDigest())
                && parent.runHardDeadlineEpochSecond() == child.runHardDeadlineEpochSecond()
                && child.issuedAtEpochSecond() >= binding.issuedAtEpochSecond()
                && child.expiresAtEpochSecond() <= parent.expiresAtEpochSecond()
                && child.allowedOperations().equals(binding.operations())
                && child.requestId().equals(binding.requestId())
                && child.nonce().equals(binding.nonce())
                && child.requestHash().equals(AuthzCanonicalHash.delegationRequestHash(parent, binding)),
                AuthzErrorCode.CONTEXT_CONFLICT);
    }

    private static String signBytes(byte[] bytes, PrivateKey key) {
        try {
            return ServiceIdentityCrypto.encodeSignature(ServiceIdentityCrypto.sign(SIGNATURE_DOMAIN,
                    AuthzContract.ALGORITHM, bytes, key));
        } catch (IllegalArgumentException exception) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
    }

    private static void verifyBytes(byte[] bytes, String algorithm, String keyId, String signature,
                                    Map<String, PublicKey> keys) {
        authenticate(AuthzContract.ALGORITHM.equals(algorithm) && keys != null && keyId != null);
        PublicKey key = keys.get(keyId);
        authenticate(key != null);
        try {
            authenticate(ServiceIdentityCrypto.verify(SIGNATURE_DOMAIN, algorithm, bytes,
                    ServiceIdentityCrypto.decodeSignature(signature), key));
        } catch (IllegalArgumentException exception) {
            throw new AuthzException(AuthzErrorCode.AUTHENTICATION_FAILED);
        }
    }

    private static void time(long issuedAt, long expiresAt, Instant now) {
        authenticate(now != null && issuedAt <= now.getEpochSecond());
        AuthzContract.check(now.getEpochSecond() < expiresAt, AuthzErrorCode.EXPIRED);
    }

    private static void authenticate(boolean condition) {
        AuthzContract.check(condition, AuthzErrorCode.AUTHENTICATION_FAILED);
    }
}
