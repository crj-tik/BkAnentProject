package com.bkanent.listing.rag;

import com.bkanent.common.authz.*;
import com.bkanent.common.listing.*;
import com.bkanent.common.rag.*;
import com.bkanent.listing.config.ListingRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ListingBusinessSourceContextAuthorizationTest {
    private static final String KEY_ID = "marketing-read-current";
    private static final String CALLER_SECRET = "listing-context-test-secret-0123456789";

    @Test
    void acceptsFreshOwnContextAndDerivesBrokerFromSignedIdentity() throws Exception {
        var fixture = fixture(List.of("listing.read"), Map.of("BROKER", 84L));
        var scope = fixture.identity.verifyAuthorizedBusinessSourceContext(fixture.request,
                fixture.envelope, fixture.authorityKeys);
        assertEquals(42, scope.actorId());
        assertEquals(84L, scope.brokerId());
        assertFalse(scope.businessWideRead());
    }

    @Test
    void acceptsExplicitBusinessWideReadWithoutInventingBrokerIdentity() throws Exception {
        var fixture = fixture(List.of("listing.read.all"), Map.of());
        var scope = fixture.identity.verifyAuthorizedBusinessSourceContext(fixture.request,
                fixture.envelope, fixture.authorityKeys);
        assertEquals(42, scope.actorId());
        assertNull(scope.brokerId());
        assertTrue(scope.businessWideRead());
    }

    @Test
    void rejectsCallerAudienceAndMissingOwnBrokerMapping() throws Exception {
        var missingBroker = fixture(List.of("listing.read"), Map.of());
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class,
                () -> missingBroker.identity.verifyAuthorizedBusinessSourceContext(missingBroker.request,
                        missingBroker.envelope, missingBroker.authorityKeys)).code());

        var wrongAudience = fixture(List.of("listing.read"), Map.of("BROKER", 84L));
        var context = wrongAudience.request.authorizationContext();
        var wrong = new RagContextAuthorizedListingBusinessBatchReadRequest(1, wrongAudience.request.requestId(),
                wrongAudience.request.request(), resignContext(context, "notification-service", wrongAudience.authority),
                wrongAudience.request.freshness());
        var envelope = signedEnvelope(wrong, context.actorId());
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class,
                () -> wrongAudience.identity.verifyAuthorizedBusinessSourceContext(wrong, envelope,
                        wrongAudience.authorityKeys)).code());
    }

    private Fixture fixture(List<String> operations, Map<String, Long> identities) throws Exception {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "c".repeat(64);
        KeyPair authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var context = AuthzSignature.sign(new AuthorizationContext("42", 9, 3, hash, operations,
                "agent-service", "parent-request", "run-1", "session-1", hash, hash, nonce,
                now - 1, now + 60, now + 90, identities, AuthzContract.ALGORITHM, "auth-test", null),
                authority.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("42", 9, 3, AuthorizationStatus.ACTIVE,
                hash, now - 1, now + 20, nonce, AuthzContract.ALGORITHM, "auth-test", null), authority.getPrivate());
        var body = new ListingSnapshotBatchReadRequest(ListingBusinessResponse.VERSION,
                List.of(new ListingSourceVersionRef(new ListingRef("listing-master-service", "55"), null)));
        var request = new RagContextAuthorizedListingBusinessBatchReadRequest(1, "listing-read-55", body,
                context, freshness);
        var trusted = new ListingRagProperties.TrustedCaller();
        trusted.setCallerService("marketing-content-service");
        trusted.setSecret(CALLER_SECRET);
        var properties = new ListingRagProperties();
        properties.setTrustedCallers(Map.of(KEY_ID, trusted));
        var identity = new ListingRagIdentity(properties, mock(ObjectProvider.class));
        Map<String, java.security.PublicKey> authorityKeys = Map.of("auth-test", authority.getPublic());
        return new Fixture(request, signedEnvelope(request, context.actorId()), identity, authority, authorityKeys);
    }

    private RagAuthEnvelope signedEnvelope(RagContextAuthorizedListingBusinessBatchReadRequest request, String actor) {
        long now = Instant.now().getEpochSecond();
        var unsigned = new RagAuthEnvelope(1, KEY_ID, ListingRagIdentity.SERVICE, "marketing-content-service",
                RagOperation.READ, request.requestId(), RagCanonicalHash.requestHash(request), RagActorMode.USER,
                actor, now, now + 15, null, null);
        return RagRequestSigner.sign(unsigned, CALLER_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private AuthorizationContext resignContext(AuthorizationContext original, String audience, KeyPair authority) {
        return AuthzSignature.sign(new AuthorizationContext(original.actorId(), original.permissionRevision(),
                original.credentialEpoch(), original.permissionHash(), original.allowedOperations(), audience,
                original.requestId(), original.runId(), original.sessionId(), original.policyDigest(),
                original.requestHash(), original.nonce(), original.issuedAtEpochSecond(),
                original.expiresAtEpochSecond(), original.runHardDeadlineEpochSecond(),
                original.businessIdentities(), AuthzContract.ALGORITHM, "auth-test", null), authority.getPrivate());
    }

    private record Fixture(RagContextAuthorizedListingBusinessBatchReadRequest request,
                           RagAuthEnvelope envelope, ListingRagIdentity identity, KeyPair authority,
                           Map<String, java.security.PublicKey> authorityKeys) { }
}
