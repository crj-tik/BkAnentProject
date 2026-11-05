package com.bkanent.customer.service;

import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedListingInquiryVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rag.RagSigningKey;
import com.bkanent.common.rpc.ListingInquiryVerifyResponse;
import com.bkanent.common.rpc.ListingInquiryVerifyRpcService;
import com.bkanent.customer.rpc.CustomerVerifyContractHash;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Customer→Listing 咨询房源验证客户端测试：签名可验证、密钥缺失/依赖异常失败关闭。
 */
class ListingInquiryVerifyClientTest {

    private static final String SECRET = "customer-inquiry-verify-test-secret-0123456789";
    private static final String KEY_ID = "customer-inquiry-verify";

    private final ListingInquiryVerifyRpcService listings = mock(ListingInquiryVerifyRpcService.class);

    private ListingInquiryVerifyClient client(String secret) {
        ListingInquiryVerifyClient client = new ListingInquiryVerifyClient(KEY_ID, secret);
        ReflectionTestUtils.setField(client, "listings", listings);
        return client;
    }

    @Test
    void signedBoundContextProducesVerifiableEnvelopeAndReturnsResponse() {
        ListingInquiryVerifyClient client = client(SECRET);
        when(listings.verifyInquiryListingContext(any(), any())).thenAnswer(call -> {
            RagContextAuthorizedListingInquiryVerifyRequest request = call.getArgument(0);
            RagAuthEnvelope envelope = call.getArgument(1);
            RagRequestSigner.verify(envelope,
                    CustomerVerifyContractHash.listingInquiryVerifyRequestHash(request),
                    "listing-master-service", RagOperation.READ, request.request().requestId(), null,
                    new RagSigningKey(KEY_ID, "customer-service", null, Set.of(RagOperation.READ),
                            SECRET.getBytes(StandardCharsets.UTF_8)), Instant.now());
            assertEquals(77L, request.request().listingId());
            assertEquals(RagActorMode.USER, envelope.actorMode());
            assertEquals("123", envelope.actorId());
            return new ListingInquiryVerifyResponse(request.request().requestId(),
                    ListingInquiryVerifyResponse.ACCESSIBLE, 77L, "ON_SALE", "v1");
        });

        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own", "listing.read"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            ListingInquiryVerifyResponse response = client.verify(77L);
            assertEquals(ListingInquiryVerifyResponse.ACCESSIBLE, response.result());
            assertEquals("ON_SALE", response.status());
            assertEquals("v1", response.listingVersion());
        }
        verify(listings).verifyInquiryListingContext(any(), any());
    }

    @Test
    void missingSecretFailsClosedWithoutCallingListing() {
        ListingInquiryVerifyClient client = client("");
        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own", "listing.read"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            RagException failure = assertThrows(RagException.class, () -> client.verify(77L));
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, failure.code());
        }
        verifyNoInteractions(listings);
    }

    @Test
    void nullOrMismatchedResponseFailsClosed() {
        ListingInquiryVerifyClient client = client(SECRET);
        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own", "listing.read"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            when(listings.verifyInquiryListingContext(any(), any())).thenReturn(null);
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(RagException.class, () -> client.verify(77L)).code());

            when(listings.verifyInquiryListingContext(any(), any())).thenReturn(
                    new ListingInquiryVerifyResponse("other-request",
                            ListingInquiryVerifyResponse.ACCESSIBLE, 77L, "ON_SALE", "v1"));
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(RagException.class, () -> client.verify(77L)).code());
        }
    }

    @Test
    void rpcFailureMapsToDependencyUnavailable() {
        ListingInquiryVerifyClient client = client(SECRET);
        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own", "listing.read"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            when(listings.verifyInquiryListingContext(any(), any()))
                    .thenThrow(new IllegalStateException("private"));
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(RagException.class, () -> client.verify(77L)).code());
        }
    }

    @Test
    void missingListingReadPermissionFailsClosedBeforeCallingListing() {
        ListingInquiryVerifyClient client = client(SECRET);
        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            assertThrows(AuthzException.class, () -> client.verify(77L));
        }
        verifyNoInteractions(listings);
    }

    @Test
    void invalidListingIdFailsClosedBeforeCallingListing() {
        ListingInquiryVerifyClient client = client(SECRET);
        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own", "listing.read"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            assertEquals(RagErrorCode.INVALID_ARGUMENT,
                    assertThrows(RagException.class, () -> client.verify(null)).code());
            assertEquals(RagErrorCode.INVALID_ARGUMENT,
                    assertThrows(RagException.class, () -> client.verify(0L)).code());
        }
        verifyNoInteractions(listings);
    }
}
