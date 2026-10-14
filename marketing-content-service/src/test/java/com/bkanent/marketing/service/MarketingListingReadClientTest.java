package com.bkanent.marketing.service;

import com.bkanent.common.a2a.A2aCredentialContext;
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
}
