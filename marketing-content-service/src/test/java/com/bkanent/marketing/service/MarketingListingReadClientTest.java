package com.bkanent.marketing.service;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.authz.*;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketingListingReadClientTest {
    private static final String SECRET = "marketing-test-only-independent-read-secret-123456";
    private final ListingMasterRpcService listings = mock(ListingMasterRpcService.class);
    private final AuthPermissionRpcService auth = mock(AuthPermissionRpcService.class);

    private MarketingListingReadClient client() {
        var client = new MarketingListingReadClient(listings, "marketing-read", SECRET);
        ReflectionTestUtils.setField(client, "auth", auth);
        return client;
    }

    private A2aCredentialContext.Lease lease(long expiry, Runnable validator) {
        var binding = new A2aBeaconBinding(1, "request", "agent-service", "marketing-content-service", "42", "POST",
                "a".repeat(64), "server-task");
        return new A2aCredentialContext.Lease(binding, RagCredentialContext.beacon("dedicated-beacon"), expiry, validator);
    }

    private ListingDTO listing(long id) { return new ListingDTO(id, "title", "address", "layout", null, null, "ON_SALE", null, null, null, null, null); }
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void dedicatedA2aLeaseProducesSignedUserReadAndIsNeverServiceFallback() {
        var client = client();
        var validation = new AtomicInteger();
        when(listings.read(any(), any(), any())).thenAnswer(call -> {
            RagListingReadRequest request = call.getArgument(0);
            RagAuthEnvelope envelope = call.getArgument(1);
            RagCredentialContext credential = call.getArgument(2);
            RagRequestSigner.verify(envelope, RagCanonicalHash.requestHash(request), "listing-master-service", RagOperation.READ,
                    request.requestId(), credential, new RagSigningKey("marketing-read", "marketing-content-service", null,
                            Set.of(RagOperation.READ), SECRET.getBytes(StandardCharsets.UTF_8)), Instant.now());
            assertEquals(7L, request.listingId()); assertNull(request.keyword());
            assertEquals(RagActorMode.USER, envelope.actorMode()); assertEquals("42", envelope.actorId());
            assertNull(credential.accessToken()); assertEquals("dedicated-beacon", credential.beacon());
            return List.of(listing(7));
        });
        try (var ignored = A2aCredentialContext.open(lease(Instant.now().getEpochSecond() + 60, validation::incrementAndGet))) {
            assertEquals(7L, client.get(7L).id());
        }
        assertTrue(validation.get() >= 2);
        assertThrows(RagException.class, A2aCredentialContext::currentCredentials);
        verifyNoInteractions(auth);
    }

    @Test void absentExpiredOrRevokedLeaseNeverReads() {
        var client = client();
        assertThrows(RagException.class, () -> client.get(7L));
        try (var ignored = A2aCredentialContext.open(lease(Instant.now().getEpochSecond() - 1, () -> {}))) {
            assertEquals(RagErrorCode.AUTHENTICATION_EXPIRED, assertThrows(RagException.class, () -> client.get(7L)).code());
        }
        try (var ignored = A2aCredentialContext.open(lease(Long.MAX_VALUE, () -> { throw new RagException(RagErrorCode.AUTHENTICATION_FAILED); }))) {
            assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class, () -> client.get(7L)).code());
        }
        verifyNoInteractions(listings, auth);
    }

    @Test void invalidHttpCredentialNeverFallsBackToValidA2aContext() {
        var client = client();
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try (var ignored = A2aCredentialContext.open(lease(Long.MAX_VALUE, () -> {}))) {
            request.addHeader("Authorization", "Basic invalid");
            assertThrows(RagException.class, () -> client.get(7L));
            request.removeHeader("Authorization"); request.addHeader("Authorization", "Bearer revoked");
            assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class, () -> client.get(7L)).code());
        }
        verifyNoInteractions(listings);
    }

    @Test void genuineHttpTokenIsDedicatedAndNullOrWrongIdResponseIsFailure() {
        var client = client();
        var request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer valid-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(auth.resolvePrincipal("valid-token")).thenReturn(new AuthenticatedPrincipal(42L));
        when(listings.read(any(), any(), any())).thenAnswer(call -> {
            RagAuthEnvelope envelope = call.getArgument(1); RagCredentialContext credential = call.getArgument(2);
            assertEquals(RagActorMode.USER, envelope.actorMode()); assertEquals("42", envelope.actorId());
            assertEquals("valid-token", credential.accessToken()); return List.of(listing(7));
        });
        assertEquals(7L, client.get(7L).id());
        doReturn(List.of(listing(8))).when(listings).read(any(), any(), any());
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(RagException.class, () -> client.get(7L)).code());
        doReturn(null).when(listings).read(any(), any(), any());
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(RagException.class, () -> client.get(7L)).code());
        doReturn(List.of()).when(listings).read(any(), any(), any());
        assertNull(client.get(7L));
    }

    @Test void signedRequestScopeUsesListingProofWithoutRepeatingAuthResolution() {
        var client = client();
        try (var ignored = MarketingAuthzScopeFixture.open(List.of("listing.read"))) {
            var authorization = AuthzRequestScope.requireCurrent();
            when(listings.readAuthorized(any(), any(), any())).thenAnswer(call -> {
                RagAuthorizedListingReadRequest request = call.getArgument(0);
                RagAuthEnvelope proof = call.getArgument(1);
                assertSame(authorization, call.getArgument(2));
                assertEquals(7L, request.request().listingId());
                assertEquals(AuthzCanonicalHash.signedResponseHash(authorization), request.authorizationEvidenceHash());
                assertNull(proof.credentialDigest());
                RagRequestSigner.verify(proof, RagCanonicalHash.requestHash(request), "listing-master-service",
                        RagOperation.READ, request.request().requestId(), null,
                        new RagSigningKey("marketing-read", "marketing-content-service", null,
                                Set.of(RagOperation.READ), SECRET.getBytes(StandardCharsets.UTF_8)), Instant.now());
                return List.of(listing(7));
            });
            assertEquals(7L, client.get(7L).id());
        }
        verify(listings).readAuthorized(any(), any(), any());
        verify(listings, never()).read(any(), any(), any());
        verify(auth, never()).resolvePrincipal(anyString());
    }

    @Test void versionedA2aLeaseForwardsSignedContextAndRequestProofWithoutBearerOrAuthCall() throws Exception {
        var key = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        long now = Instant.now().getEpochSecond();
        String keyId = "a2a-auth-test";
        String permissionHash = "a".repeat(64);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var context = AuthzSignature.sign(new AuthorizationContext("42", 9, 0, permissionHash,
                List.of("listing.read"), "agent-service", "parent-request", "run-1", "session-1",
                "b".repeat(64), "c".repeat(64), nonce, now, now + 60, now + 300, java.util.Map.of("BROKER", 84L),
                AuthzContract.ALGORITHM, keyId, null), key.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("42", 9, 0, AuthorizationStatus.ACTIVE,
                permissionHash, now, now + 30, nonce, AuthzContract.ALGORITHM, keyId, null), key.getPrivate());
        var lease = mock(A2aCredentialContext.Lease.class);
        when(lease.versioned()).thenReturn(true);
        when(lease.authorizationContext()).thenReturn(context);
        when(lease.freshness()).thenReturn(freshness);
        when(listings.readAuthorizedContext(any(), any())).thenAnswer(call -> {
            RagContextAuthorizedListingReadRequest request = call.getArgument(0);
            RagAuthEnvelope proof = call.getArgument(1);
            assertEquals(7L, request.request().listingId());
            assertSame(context, request.authorizationContext());
            assertSame(freshness, request.freshness());
            assertEquals("42", proof.actorId());
            assertEquals(RagActorMode.USER, proof.actorMode());
            assertNull(proof.credentialDigest());
            RagRequestSigner.verify(proof, RagCanonicalHash.requestHash(request), "listing-master-service",
                    RagOperation.READ, request.request().requestId(), null,
                    new RagSigningKey("marketing-read", "marketing-content-service", null,
                            Set.of(RagOperation.READ), SECRET.getBytes(StandardCharsets.UTF_8)), Instant.now());
            return List.of(listing(7));
        });
        try (var ignored = A2aCredentialContext.open(lease)) {
            assertEquals(7L, client().get(7L).id());
        }
        verify(lease).verify();
        verify(lease).requireOperation("listing.read");
        verify(listings).readAuthorizedContext(any(), any());
        verify(listings, never()).read(any(), any(), any());
        verify(auth, never()).resolvePrincipal(anyString());
    }

    @Test void signedScopeNeedsListingReadPermissionBeforeAnyRemoteCall() throws Exception {
        var client = client();
        try (var ignored = MarketingAuthzScopeFixture.open(List.of("marketing.content.create.own"))) {
            assertThrows(AuthzException.class, () -> client.get(7L));
        }
        verifyNoInteractions(listings, auth);
    }

    private static final class MarketingAuthzScopeFixture {
        private static final long NOW = 1_800_000_000L;
        private static final String TOKEN = "marketing-authz-test-token";
        private static final String KEY_ID = "auth-test-key";
        private static final byte[] BODY = new byte[0];
        private static final String HASH = AuthzCanonicalHash.bodyHash(BODY);
        private static final String NONCE = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[32]);

        static AuthzRequestScope.Scope open(List<String> permissions) {
            try {
                var key = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                String path = "/marketing/contents/7";
                var binding = new RequestBinding("gateway", "marketing-content-service", "GET", path, path,
                        List.of(), "marketing-request", null, null, HASH, HASH, NONCE, NOW, NOW + 60);
                var snapshot = AuthzSignature.sign(new AuthorizationSnapshot("123", 1, 1, false,
                        permissions, null, java.util.Map.of("BROKER", 77L), AuthzContract.ALGORITHM,
                        KEY_ID, null), key.getPrivate());
                String permissionHash = AuthzCanonicalHash.snapshotHash(snapshot);
                String requestHash = AuthzCanonicalHash.resolveRequestHash(TOKEN, binding);
                var context = AuthzSignature.sign(new AuthorizationContext("123", 1, 0, permissionHash,
                        permissions, "marketing-content-service", binding.requestId(), null, null, HASH,
                        requestHash, NONCE, NOW, NOW + 60, NOW + 300, snapshot.businessIdentities(),
                        AuthzContract.ALGORITHM, KEY_ID, null), key.getPrivate());
                var freshness = AuthzSignature.sign(new AuthzFreshness("123", 1, 0, AuthorizationStatus.ACTIVE,
                        permissionHash, NOW, NOW + 30, NONCE, AuthzContract.ALGORITHM, KEY_ID, null), key.getPrivate());
                return AuthzRequestScope.verifyAndOpen(new AuthzResponse(snapshot, context, freshness), binding,
                        TOKEN, "marketing-content-service", "GET", path, null, BODY,
                        java.util.Map.of(KEY_ID, key.getPublic()), Instant.ofEpochSecond(NOW));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
