package com.bkanent.common.rag;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagSigningTest {
    private static final Instant NOW = Instant.ofEpochSecond(1000);
    private static final byte[] SECRET = new byte[32];
    private static final String HASH = RagCanonicalHash.requestHash(RagSearchRequest.defaults("r", "listing.knowledge", "q"));
    private static final RagCredentialContext CREDENTIALS = new RagCredentialContext("access-token-MUST-NOT-LOG");
    private static final RagSigningKey KEY = new RagSigningKey("agent-1", "agent-service", "agent", Set.of(RagOperation.SEARCH), SECRET);

    @Test
    void requestRoundTripAndTokenBinding() {
        RagAuthEnvelope signed = signedUser();
        verify(signed, CREDENTIALS, KEY, NOW);
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> verify(signed, new RagCredentialContext("different"), KEY, NOW));
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> verify(signed, null, KEY, NOW));
    }

    @Test
    void signatureCoversEveryEnvelopeFieldIncludingKeyActorAndExpiry() {
        RagAuthEnvelope signed = signedUser();
        List<RagAuthEnvelope> mutations = List.of(
                envelope(2, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH, "r", HASH, RagActorMode.USER, "1", 1000, 1060, signed.credentialDigest(), signed.signature()),
                envelope(1, "agent-2", "rag-service", "agent-service", RagOperation.SEARCH, "r", HASH, RagActorMode.USER, "1", 1000, 1060, signed.credentialDigest(), signed.signature()),
                envelope(1, "agent-1", "auth-service", "agent-service", RagOperation.SEARCH, "r", HASH, RagActorMode.USER, "1", 1000, 1060, signed.credentialDigest(), signed.signature()),
                envelope(1, "agent-1", "rag-service", "gateway", RagOperation.SEARCH, "r", HASH, RagActorMode.USER, "1", 1000, 1060, signed.credentialDigest(), signed.signature()),
                envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SUBMIT, "r", HASH, RagActorMode.USER, "1", 1000, 1060, signed.credentialDigest(), signed.signature()),
                envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH, "r2", HASH, RagActorMode.USER, "1", 1000, 1060, signed.credentialDigest(), signed.signature()),
                envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH, "r", "0".repeat(64), RagActorMode.USER, "1", 1000, 1060, signed.credentialDigest(), signed.signature()),
                envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH, "r", HASH, RagActorMode.SERVICE, "1", 1000, 1060, signed.credentialDigest(), signed.signature()),
                envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH, "r", HASH, RagActorMode.USER, "2", 1000, 1060, signed.credentialDigest(), signed.signature()),
                envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH, "r", HASH, RagActorMode.USER, "1", 999, 1060, signed.credentialDigest(), signed.signature()),
                envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH, "r", HASH, RagActorMode.USER, "1", 1000, 1059, signed.credentialDigest(), signed.signature()),
                envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH, "r", HASH, RagActorMode.USER, "1", 1000, 1060, "0".repeat(64), signed.signature()));
        mutations.forEach(mutation -> error(RagErrorCode.AUTHENTICATION_FAILED, () -> verify(mutation, CREDENTIALS, KEY, NOW)));
    }

    @Test
    void wrongAudienceOperationRequestHashAndRequestIdRejectEvenValidSignatures() {
        RagAuthEnvelope signed = signedUser();
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagRequestSigner.verify(signed, HASH, "auth-service", RagOperation.SEARCH, "r", CREDENTIALS, KEY, NOW));
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagRequestSigner.verify(signed, HASH, "rag-service", RagOperation.SUBMIT, "r", CREDENTIALS, KEY, NOW));
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagRequestSigner.verify(signed, "0".repeat(64), "rag-service", RagOperation.SEARCH, "r", CREDENTIALS, KEY, NOW));
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagRequestSigner.verify(signed, HASH, "rag-service", RagOperation.SEARCH, "r2", CREDENTIALS, KEY, NOW));
        error(RagErrorCode.FORBIDDEN, () -> verify(signed, CREDENTIALS,
                new RagSigningKey("agent-1", "agent-service", "agent", Set.of(RagOperation.SUBMIT), SECRET), NOW));
    }

    @Test
    void expiryWindowAndFutureTimestampCannotBeBypassed() {
        error(RagErrorCode.AUTHENTICATION_EXPIRED, () -> verify(signedUser(), CREDENTIALS, KEY, Instant.ofEpochSecond(1060)));
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> verify(signedUser(), CREDENTIALS, KEY, Instant.ofEpochSecond(999)));
        RagAuthEnvelope longTtl = RagRequestSigner.sign(envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH, "r", HASH,
                RagActorMode.USER, "1", 1000, 1061, RagCanonicalHash.credentialDigest(CREDENTIALS), null), SECRET);
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> verify(longTtl, CREDENTIALS, KEY, NOW));
        RagAuthEnvelope overflow = RagRequestSigner.sign(envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH, "r", HASH,
                RagActorMode.USER, "1", -1, Long.MAX_VALUE, RagCanonicalHash.credentialDigest(CREDENTIALS), null), SECRET);
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> verify(overflow, CREDENTIALS, KEY, NOW));
    }

    @Test
    void managedKeyRotationAndDefensiveSecretCopies() {
        byte[] input = new byte[32];
        input[0] = 3;
        RagSigningKey rotated = new RagSigningKey("agent-2", "agent-service", "agent", Set.of(RagOperation.SEARCH), input);
        input[0] = 4;
        byte[] returned = rotated.secret();
        returned[0] = 5;
        assertThat(rotated.secret()[0]).isEqualTo((byte) 3);
        RagAuthEnvelope signed = RagRequestSigner.sign(envelope(1, "agent-2", "rag-service", "agent-service", RagOperation.SEARCH, "r", HASH,
                RagActorMode.USER, "1", 1000, 1060, RagCanonicalHash.credentialDigest(CREDENTIALS), null), rotated.secret());
        verify(signed, CREDENTIALS, rotated, NOW);
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> verify(signed, CREDENTIALS, KEY, NOW));
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> verify(signed, CREDENTIALS,
                new RagSigningKey("agent-2", "agent-service", "agent", Set.of(RagOperation.SEARCH), SECRET), NOW));
        error(RagErrorCode.INVALID_ARGUMENT, () -> new RagSigningKey("a", "b", null, Set.of(RagOperation.SEARCH), new byte[31]));
    }

    @Test
    void serviceActorCanWriteButNeverSearchOrImpersonateUser() {
        RagSigningKey writer = new RagSigningKey("listing-1", "listing-master-service", "listing-master",
                Set.of(RagOperation.SUBMIT, RagOperation.GET_STATUS, RagOperation.SEARCH), SECRET);
        RagAuthEnvelope write = RagRequestSigner.sign(envelope(1, writer.keyId(), "rag-service", writer.callerService(), RagOperation.SUBMIT,
                "r", HASH, RagActorMode.SERVICE, writer.callerService(), 1000, 1060, null, null), SECRET);
        RagRequestSigner.verify(write, HASH, "rag-service", RagOperation.SUBMIT, "r", null, writer, NOW);
        RagAuthEnvelope search = RagRequestSigner.sign(envelope(1, writer.keyId(), "rag-service", writer.callerService(), RagOperation.SEARCH,
                "r", HASH, RagActorMode.SERVICE, writer.callerService(), 1000, 1060, null, null), SECRET);
        error(RagErrorCode.FORBIDDEN, () -> RagRequestSigner.verify(search, HASH, "rag-service", RagOperation.SEARCH, "r", null, writer, NOW));
        RagAuthEnvelope fakeActor = RagRequestSigner.sign(envelope(1, writer.keyId(), "rag-service", writer.callerService(), RagOperation.SUBMIT,
                "r", HASH, RagActorMode.SERVICE, "1", 1000, 1060, null, null), SECRET);
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagRequestSigner.verify(fakeActor, HASH, "rag-service", RagOperation.SUBMIT, "r", null, writer, NOW));
    }

    @Test
    void ragToAuthIsServiceIdentityBoundToDedicatedUserTokenAndSignedResponseFacts() {
        RagSigningKey rag = new RagSigningKey("rag-1", "rag-service", null, Set.of(RagOperation.RESOLVE_PRINCIPAL), SECRET);
        String tokenHash = RagCanonicalHash.credentialDigest(CREDENTIALS);
        RagAuthEnvelope request = RagRequestSigner.sign(envelope(1, rag.keyId(), "auth-service", rag.callerService(), RagOperation.RESOLVE_PRINCIPAL,
                "r", tokenHash, RagActorMode.SERVICE, rag.callerService(), 1000, 1060, tokenHash, null), SECRET);
        RagRequestSigner.verify(request, tokenHash, "auth-service", RagOperation.RESOLVE_PRINCIPAL, "r", CREDENTIALS, rag, NOW);
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagRequestSigner.verify(request, tokenHash, "auth-service", RagOperation.RESOLVE_PRINCIPAL, "r",
                new RagCredentialContext("forged"), rag, NOW));
        AuthRagPrincipal principal = new AuthRagPrincipal(1L, "ADMIN", true, true, 1100);
        RagSigningKey auth = new RagSigningKey("auth-1", "auth-service", null, Set.of(RagOperation.RESOLVE_PRINCIPAL_RESPONSE), SECRET);
        RagAuthEnvelope response = RagRequestSigner.sign(envelope(1, auth.keyId(), "rag-service", auth.callerService(), RagOperation.RESOLVE_PRINCIPAL_RESPONSE,
                "r", RagCanonicalHash.principalHash(principal), RagActorMode.USER, "1", 1000, 1060, tokenHash, null), SECRET);
        RagRequestSigner.verify(response, RagCanonicalHash.principalHash(principal), "rag-service", RagOperation.RESOLVE_PRINCIPAL_RESPONSE,
                "r", CREDENTIALS, auth, NOW);
        AuthRagPrincipal forged = new AuthRagPrincipal(1L, "ADMIN", true, false, 1100, response);
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagRequestSigner.verify(response, RagCanonicalHash.principalHash(forged), "rag-service",
                RagOperation.RESOLVE_PRINCIPAL_RESPONSE, "r", CREDENTIALS, auth, NOW));
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagRequestSigner.verify(null, RagCanonicalHash.principalHash(principal), "rag-service",
                RagOperation.RESOLVE_PRINCIPAL_RESPONSE, "r", CREDENTIALS, auth, NOW));
    }

    @Test
    void scopeRequiresRegisteredOwnerKeyAndBindsSpaceActorRequestPolicyAndResources() {
        RagSigningKey owner = ownerKey();
        RagAuthorizationScope scope = RagScopeSigner.sign(scope("listing.knowledge", "1", HASH, "p1", null, List.of("2", "1", "2"), true), SECRET);
        RagScopeSigner.verify(scope, owner, "listing.knowledge", "1", RagOperation.SEARCH, HASH, "listing-master", "p1", NOW);
        assertThat(scope.allowedResourceIds()).containsExactly("1", "2");
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagScopeSigner.verify(scope, owner, "memory.preference", "1", RagOperation.SEARCH, HASH, "listing-master", "p1", NOW));
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagScopeSigner.verify(scope, owner, "listing.knowledge", "2", RagOperation.SEARCH, HASH, "listing-master", "p1", NOW));
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagScopeSigner.verify(scope, owner, "listing.knowledge", "1", RagOperation.SEARCH, "0".repeat(64), "listing-master", "p1", NOW));
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagScopeSigner.verify(scope, owner, "listing.knowledge", "1", RagOperation.SEARCH, HASH, "listing-master", "p2", NOW));
        error(RagErrorCode.FORBIDDEN, () -> RagScopeSigner.verify(scope,
                new RagSigningKey(owner.keyId(), owner.callerService(), "agent", Set.of(RagOperation.SEARCH), SECRET),
                "listing.knowledge", "1", RagOperation.SEARCH, HASH, "listing-master", "p1", NOW));
        RagAuthorizationScope tampered = new RagAuthorizationScope(1, scope.keyId(), scope.issuerService(), scope.ownerDomain(), scope.spaceId(),
                scope.actorId(), scope.operation(), scope.requestHash(), scope.policyVersion(), scope.issuedAtEpochSecond(), scope.expiresAtEpochSecond(),
                scope.ownerId(), List.of("3"), scope.activeOnly(), scope.signature());
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagScopeSigner.verify(tampered, owner, "listing.knowledge", "1", RagOperation.SEARCH, HASH, "listing-master", "p1", NOW));
        error(RagErrorCode.AUTHENTICATION_EXPIRED, () -> RagScopeSigner.verify(scope, owner, "listing.knowledge", "1", RagOperation.SEARCH, HASH, "listing-master", "p1", Instant.ofEpochSecond(1060)));
    }

    @Test
    void emptyListingScopeIsDeniedAndOversizedScopeNeverDropsFilter() {
        RagAuthorizationScope empty = RagScopeSigner.sign(scope("listing.knowledge", "1", HASH, "p1", null, List.of(), true), SECRET);
        error(RagErrorCode.FORBIDDEN, () -> RagScopeSigner.verify(empty, ownerKey(), "listing.knowledge", "1", RagOperation.SEARCH, HASH, "listing-master", "p1", NOW));
        error(RagErrorCode.RESOURCE_SCOPE_TOO_LARGE, () -> scope("listing.knowledge", "1", HASH, "p1", null,
                IntStream.range(0, 5001).mapToObj(Integer::toString).toList(), true));
    }

    @Test
    void memoryOwnerAndActiveConstraintAreMandatory() {
        RagSigningKey memory = new RagSigningKey("memory-1", "memory-service", "memory", Set.of(RagOperation.SEARCH), SECRET);
        RagAuthorizationScope preference = memoryScope("memory.preference", "1", "1", true);
        RagScopeSigner.verify(preference, memory, "memory.preference", "1", RagOperation.SEARCH, HASH, "memory", "p1", NOW);
        error(RagErrorCode.FORBIDDEN, () -> RagScopeSigner.verify(memoryScope("memory.preference", "1", null, true), memory,
                "memory.preference", "1", RagOperation.SEARCH, HASH, "memory", "p1", NOW));
        error(RagErrorCode.FORBIDDEN, () -> RagScopeSigner.verify(memoryScope("memory.preference", "1", "2", true), memory,
                "memory.preference", "1", RagOperation.SEARCH, HASH, "memory", "p1", NOW));
        error(RagErrorCode.FORBIDDEN, () -> RagScopeSigner.verify(memoryScope("memory.constraint", "1", null, false), memory,
                "memory.constraint", "1", RagOperation.SEARCH, HASH, "memory", "p1", NOW));
    }

    @Test
    void versionedScopeSignatureCommitsToNestedAuthEvidence() {
        RagAuthEnvelope attestation = RagRequestSigner.sign(new RagAuthEnvelope(1, "auth-key",
                "listing-master-service", "auth-service", RagOperation.RESOLVE_PRINCIPAL_RESPONSE,
                "principal-req", "c".repeat(64), RagActorMode.USER, "1", 1000, 1060,
                "d".repeat(64), null), SECRET);
        AuthRagPrincipal principal = new AuthRagPrincipal(1L, "BROKER", true, true, 1100,
                AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION, 17, 4, false,
                List.of("BROKER", "SALES"), List.of("listing.read"), attestation);
        RagAuthorizationScope signed = RagScopeSigner.sign(new RagAuthorizationScope(1, "listing-1",
                "listing-master-service", "listing-master", "listing.knowledge", "1", RagOperation.SEARCH,
                HASH, "p1", 1000, 1060, null, List.of(), true, true,
                AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION, 17, 4, false, "listing.read",
                principal, null, null), SECRET);
        RagSigningKey key = ownerKey();
        RagScopeSigner.verify(signed, key, "listing.knowledge", "1", RagOperation.SEARCH,
                HASH, "listing-master", "p1", NOW);

        AuthRagPrincipal changedPrincipal = new AuthRagPrincipal(1L, "BROKER", true, true, 1100,
                AuthRagPrincipal.VERSIONED_AUTHORIZATION_VERSION, 17, 4, false,
                List.of("BROKER", "SALES"), List.of("listing.read", "listing.write"), attestation);
        RagAuthorizationScope tampered = new RagAuthorizationScope(signed.contractVersion(), signed.keyId(),
                signed.issuerService(), signed.ownerDomain(), signed.spaceId(), signed.actorId(), signed.operation(),
                signed.requestHash(), signed.policyVersion(), signed.issuedAtEpochSecond(), signed.expiresAtEpochSecond(),
                signed.ownerId(), signed.allowedResourceIds(), signed.activeOnly(), signed.businessWideRead(),
                signed.authorizationVersion(), signed.permissionRevision(), signed.credentialEpoch(), signed.businessAdmin(),
                signed.operationCode(), changedPrincipal, null, signed.signature());
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagScopeSigner.verify(tampered, key,
                "listing.knowledge", "1", RagOperation.SEARCH, HASH, "listing-master", "p1", NOW));
    }

    @Test
    void credentialKeyAndPayloadToStringsAndFailuresDoNotLeakSecrets() {
        assertThat(CREDENTIALS.toString()).doesNotContain(CREDENTIALS.accessToken());
        assertThat(KEY.toString()).doesNotContain(Arrays.toString(SECRET));
        assertThat(signedUser().toString()).doesNotContain(signedUser().signature(), signedUser().credentialDigest());
        assertThat(new RagDocument("type", "id", "private text", Map.of()).toString()).doesNotContain("private text");
        assertThat(new RagException(RagErrorCode.FORBIDDEN, "access-token-MUST-NOT-LOG").toString()).doesNotContain("access-token");
        error(RagErrorCode.INVALID_ARGUMENT, () -> RagCanonicalHash.requestHash(CREDENTIALS));
        error(RagErrorCode.INVALID_ARGUMENT, () -> RagCanonicalHash.requestHash(KEY));
        error(RagErrorCode.INVALID_ARGUMENT, () -> RagCanonicalHash.requestHash(signedUser()));
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> verify(envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH,
                "r", HASH, RagActorMode.USER, "1", 1000, 1060, RagCanonicalHash.credentialDigest(CREDENTIALS), CREDENTIALS.accessToken()), CREDENTIALS, KEY, NOW));
    }

    @Test
    void businessWideReadIsExplicitSignedAndListingSearchOnly() {
        var wide = RagScopeSigner.sign(new RagAuthorizationScope(1, "listing-1", "listing-master-service", "listing-master",
                "listing.knowledge", "1", RagOperation.SEARCH, HASH, "p1", 1000, 1060, null, List.of(), false, true, null), SECRET);
        RagScopeSigner.verify(wide, ownerKey(), "listing.knowledge", "1", RagOperation.SEARCH, HASH, "listing-master", "p1", NOW);
        assertThat(scope("listing.knowledge", "1", HASH, "p1", null, List.of("1"), true).businessWideRead()).isFalse();
        var tampered = new RagAuthorizationScope(1, wide.keyId(), wide.issuerService(), wide.ownerDomain(), wide.spaceId(),
                wide.actorId(), wide.operation(), wide.requestHash(), wide.policyVersion(), 1000, 1060, null, List.of(), false, false, wide.signature());
        error(RagErrorCode.AUTHENTICATION_FAILED, () -> RagScopeSigner.verify(tampered, ownerKey(), "listing.knowledge", "1",
                RagOperation.SEARCH, HASH, "listing-master", "p1", NOW));
        var memory = RagScopeSigner.sign(new RagAuthorizationScope(1, "memory-1", "memory-service", "memory", "memory.preference",
                "1", RagOperation.SEARCH, HASH, "p1", 1000, 1060, "1", List.of(), true, true, null), SECRET);
        error(RagErrorCode.FORBIDDEN, () -> RagScopeSigner.verify(memory,
                new RagSigningKey("memory-1", "memory-service", "memory", Set.of(RagOperation.SEARCH), SECRET),
                "memory.preference", "1", RagOperation.SEARCH, HASH, "memory", "p1", NOW));
    }

    @Test
    void credentialsAreExclusiveAndTypeDomainSeparated() {
        var token = new RagCredentialContext("same-reference");
        var beacon = RagCredentialContext.beacon("same-reference");
        assertThat(RagCanonicalHash.credentialDigest(token)).isNotEqualTo(RagCanonicalHash.credentialDigest(beacon));
        assertThat(beacon.toString()).doesNotContain("same-reference");
        error(RagErrorCode.INVALID_ARGUMENT, () -> new RagCredentialContext(null, null));
        error(RagErrorCode.INVALID_ARGUMENT, () -> new RagCredentialContext("token", "beacon"));
        error(RagErrorCode.INVALID_ARGUMENT, () -> new RagCredentialContext("token", ""));
        assertThat(RagCanonicalHash.requestHash(new RagListingReadRequest(1, "r", 1L, null))).hasSize(64);
        error(RagErrorCode.INVALID_ARGUMENT, () -> new RagListingReadRequest(1, "r", 1L, "q"));
    }

    private static RagAuthEnvelope signedUser() {
        return RagRequestSigner.sign(envelope(1, "agent-1", "rag-service", "agent-service", RagOperation.SEARCH,
                "r", HASH, RagActorMode.USER, "1", 1000, 1060, RagCanonicalHash.credentialDigest(CREDENTIALS), null), SECRET);
    }

    private static RagAuthEnvelope envelope(int version, String keyId, String audience, String caller, RagOperation op,
                                            String requestId, String hash, RagActorMode actorMode, String actor,
                                            long issued, long expires, String digest, String signature) {
        return new RagAuthEnvelope(version, keyId, audience, caller, op, requestId, hash, actorMode, actor, issued, expires, digest, signature);
    }

    private static void verify(RagAuthEnvelope envelope, RagCredentialContext credentials, RagSigningKey key, Instant now) {
        RagRequestSigner.verify(envelope, HASH, "rag-service", RagOperation.SEARCH, "r", credentials, key, now);
    }

    private static RagSigningKey ownerKey() {
        return new RagSigningKey("listing-1", "listing-master-service", "listing-master", Set.of(RagOperation.SEARCH), SECRET);
    }

    private static RagAuthorizationScope scope(String space, String actor, String hash, String policy, String owner,
                                              List<String> ids, Boolean active) {
        return new RagAuthorizationScope(1, "listing-1", "listing-master-service", "listing-master", space, actor,
                RagOperation.SEARCH, hash, policy, 1000, 1060, owner, ids, active, null);
    }

    private static RagAuthorizationScope memoryScope(String space, String actor, String owner, Boolean active) {
        return RagScopeSigner.sign(new RagAuthorizationScope(1, "memory-1", "memory-service", "memory", space, actor,
                RagOperation.SEARCH, HASH, "p1", 1000, 1060, owner, List.of(), active, null), SECRET);
    }

    private static void error(RagErrorCode code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(RagException.class).extracting("code").isEqualTo(code);
    }
}
