package com.bkanent.customer.service;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.*;
import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzRequestScope;
import com.bkanent.customer.converter.CustomerConverter;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.service.impl.CustomerManagementServiceImpl;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingReadClientTest {
    private static final String SECRET = "customer-test-only-independent-read-secret-123456";
    private final ListingMasterRpcService listings = mock(ListingMasterRpcService.class);
    private final AuthPermissionRpcService auth = mock(AuthPermissionRpcService.class);

    private ListingReadClient client() {
        var client = new ListingReadClient("customer-read", SECRET);
        ReflectionTestUtils.setField(client, "listings", listings);
        ReflectionTestUtils.setField(client, "auth", auth);
        return client;
    }

    private void request(String authorization) {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Gateway-Authenticated", "true");
        request.addHeader("X-User-Id", "42");
        if (authorization != null) request.addHeader("Authorization", authorization);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void realHttpTokenProducesPayloadAndCredentialBoundUserReadNotService() {
        var client = client();
        request("Bearer valid-token");
        when(auth.resolvePrincipal("valid-token")).thenReturn(new AuthenticatedPrincipal(42L));
        when(listings.read(any(), any(), any())).thenAnswer(call -> {
            RagListingReadRequest payload = call.getArgument(0);
            RagAuthEnvelope envelope = call.getArgument(1);
            RagCredentialContext credential = call.getArgument(2);
            RagRequestSigner.verify(envelope, RagCanonicalHash.requestHash(payload), "listing-master-service", RagOperation.READ,
                    payload.requestId(), credential, new RagSigningKey("customer-read", "customer-service", null,
                            Set.of(RagOperation.READ), SECRET.getBytes(StandardCharsets.UTF_8)), Instant.now());
            assertEquals("region", payload.keyword());
            assertNull(payload.listingId());
            assertEquals(RagActorMode.USER, envelope.actorMode());
            assertEquals("42", envelope.actorId());
            assertEquals("valid-token", credential.accessToken());
            return List.of();
        });
        assertTrue(client.search("region", client.authenticate()).isEmpty());
        verify(listings).read(any(), any(), any());
    }

    @Test void signedRequestContextUsesLocalListingProofWithoutForwardingBearerOrCallingAuth() {
        var client = client();
        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.match.own", "listing.read"), java.util.Map.of("BROKER", 41L))) {
            var authorization = AuthzRequestScope.requireCurrent();
            when(listings.readAuthorized(any(), any(), any())).thenAnswer(call -> {
                RagAuthorizedListingReadRequest request = call.getArgument(0);
                RagAuthEnvelope proof = call.getArgument(1);
                assertSame(authorization, call.getArgument(2));
                assertEquals("region", request.request().keyword());
                assertEquals(AuthzCanonicalHash.signedResponseHash(authorization), request.authorizationEvidenceHash());
                assertNull(proof.credentialDigest());
                RagRequestSigner.verify(proof, RagCanonicalHash.requestHash(request), "listing-master-service",
                        RagOperation.READ, request.request().requestId(), null,
                        new RagSigningKey("customer-read", "customer-service", null,
                                java.util.Set.of(RagOperation.READ), SECRET.getBytes(StandardCharsets.UTF_8)), Instant.now());
                return List.of();
            });

            assertTrue(client.search("region", client.authenticate()).isEmpty());
        }
        verify(listings).readAuthorized(any(), any(), any());
        verify(listings, never()).read(any(), any(), any());
        verify(auth, never()).resolvePrincipal(anyString());
    }

    @Test void signedContextCannotReadListingsWithoutTheListingOperation() {
        var client = client();
        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.match.own"), java.util.Map.of("BROKER", 41L))) {
            assertThrows(AuthzException.class, client::authenticate);
        }
        verifyNoInteractions(listings, auth);
    }

    @Test void missingMalformedRevokedAndUnavailableAuthenticationNeverReadListings() {
        var client = client();
        assertThrows(RagException.class, client::authenticate);
        request(null); assertThrows(RagException.class, client::authenticate);
        request("Basic fake"); assertThrows(RagException.class, client::authenticate);
        request("Bearer revoked"); assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class, client::authenticate).code());
        when(auth.resolvePrincipal("revoked")).thenThrow(new IllegalStateException("private backend error"));
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(RagException.class, client::authenticate).code());
        verifyNoInteractions(listings);
    }

    @Test void nullOrUnavailableReadIsNotEmptySuccess() {
        var client = client();
        request("Bearer valid-token");
        when(auth.resolvePrincipal("valid-token")).thenReturn(new AuthenticatedPrincipal(42L));
        var user = client.authenticate();
        when(listings.read(any(), any(), any())).thenReturn(null);
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(RagException.class, () -> client.search("region", user)).code());
        when(listings.read(any(), any(), any())).thenThrow(new IllegalStateException("private"));
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(RagException.class, () -> client.search("region", user)).code());
    }

    @Test void matchingChecksTheMappedCustomerBeforeListingLookupAndEmptyPreferenceShortcut() {
        var client = mock(ListingReadClient.class);
        var customers = mock(CustomerDomainService.class);
        var management = new CustomerManagementServiceImpl(customers, mock(CustomerFollowRecordDomainService.class),
                mock(OwnerEntrustRecordDomainService.class), mock(CustomerFavoriteListingDomainService.class),
                new CustomerConverter(), client);
        var customer = new CustomerEntity(); customer.setId(9L); customer.setBrokerId(41L);
        when(customers.getById(9L)).thenReturn(customer);
        when(client.authenticate()).thenThrow(new RagException(RagErrorCode.AUTHENTICATION_FAILED));
        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.match.own"), java.util.Map.of("BROKER", 41L))) {
            assertThrows(RagException.class, () -> management.matchListings(9L));
            verify(customers).getById(9L);
        }
        var user = new ListingReadClient.UserRead("42", new RagCredentialContext("valid-token"));
        doReturn(user).when(client).authenticate();
        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.match.own"), java.util.Map.of("BROKER", 41L))) {
            assertTrue(management.matchListings(9L).isEmpty());
            customer.setPreferredArea("region");
            when(client.search("region", user)).thenReturn(List.of());
            assertTrue(management.matchListings(9L).isEmpty());
            verify(client).search("region", user);
        }
    }
}
