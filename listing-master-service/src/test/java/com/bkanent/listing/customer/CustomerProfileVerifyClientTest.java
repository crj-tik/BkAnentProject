package com.bkanent.listing.customer;

import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rag.RagSigningKey;
import com.bkanent.common.rag.RagContextAuthorizedCustomerProfileVerifyRequest;
import com.bkanent.common.rpc.CustomerProfileVerifyResponse;
import com.bkanent.common.rpc.CustomerProfileVerifyRpcService;
import com.bkanent.listing.rag.ListingAuthzScopeFixture;
import com.bkanent.listing.rag.ListingVerifyContractHash;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 业主档案验证调用方：context-authorized 签名 RPC 与失败关闭语义。 */
class CustomerProfileVerifyClientTest {

    private static final String SECRET = "listing-customer-verify-test-secret-0123456789";
    private static final String KEY_ID = "customer-verify-current";

    private CustomerProfileVerifyClient client(CustomerProfileVerifyRpcService rpc, String secret) {
        var client = new CustomerProfileVerifyClient();
        ReflectionTestUtils.setField(client, "customerProfileVerify", rpc);
        ReflectionTestUtils.setField(client, "keyId", KEY_ID);
        ReflectionTestUtils.setField(client, "secret", secret);
        return client;
    }

    @Test
    void missingSecretOrDependencyFailsClosedWithoutRpc() {
        var rpc = mock(CustomerProfileVerifyRpcService.class);
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(RagException.class, () -> client(rpc, "").verifyOwnerProfile(55L)).code());
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(RagException.class, () -> client(rpc, "short-secret").verifyOwnerProfile(55L)).code());
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(RagException.class, () -> client(null, SECRET).verifyOwnerProfile(55L)).code());
        verifyNoInteractions(rpc);
    }

    @Test
    void missingVerifiedContextFailsClosed() {
        var rpc = mock(CustomerProfileVerifyRpcService.class);
        var client = client(rpc, SECRET);
        assertThrows(RagException.class, () -> client.verifyOwnerProfile(55L));
        verifyNoInteractions(rpc);
    }

    @Test
    void verifiedProfileReturnsSummaryAndSignsEnvelopeForCustomerService() {
        var rpc = mock(CustomerProfileVerifyRpcService.class);
        when(rpc.verifyOwnerProfileContext(any(), any())).thenAnswer(call -> {
            var request = (RagContextAuthorizedCustomerProfileVerifyRequest) call.getArgument(0);
            var envelope = (RagAuthEnvelope) call.getArgument(1);
            assertEquals(1, request.contractVersion());
            assertEquals(55L, request.request().customerId());
            assertEquals("OWNER", request.request().expectedProfileType());
            assertEquals("123", request.authorizationContext().actorId());
            assertEquals("run-1", request.authorizationContext().runId());
            assertEquals("session-1", request.authorizationContext().sessionId());
            RagRequestSigner.verify(envelope, ListingVerifyContractHash.customerProfileVerifyRequestHash(request), "customer-service",
                    RagOperation.READ, request.request().requestId(), null,
                    new RagSigningKey(KEY_ID, "listing-master-service", null,
                            Set.of(RagOperation.READ), SECRET.getBytes(StandardCharsets.UTF_8)), Instant.now());
            assertEquals(RagOperation.READ, envelope.operation());
            assertEquals("123", envelope.actorId());
            return new CustomerProfileVerifyResponse(request.request().requestId(),
                    CustomerProfileVerifyResponse.VERIFIED, 55L, "OWNER", 9L, 1_800_000_000L);
        });
        var client = client(rpc, SECRET);
        try (var ignored = ListingAuthzScopeFixture.openWithExecution(false,
                List.of("customer.read", "listing.create"), List.of("customer.read", "listing.create"),
                Map.of("BROKER", 9L), "run-1", "session-1")) {
            var profile = client.verifyOwnerProfile(55L);
            assertEquals(55L, profile.customerId());
            assertEquals("OWNER", profile.profileType());
            assertEquals(9L, profile.brokerId());
        }
        verify(rpc).verifyOwnerProfileContext(any(), any());
    }

    @Test
    void businessAdminSelectsCustomerReadAllOperation() {
        var rpc = mock(CustomerProfileVerifyRpcService.class);
        when(rpc.verifyOwnerProfileContext(any(), any())).thenAnswer(call -> {
            var request = (RagContextAuthorizedCustomerProfileVerifyRequest) call.getArgument(0);
            assertTrue(request.authorizationContext().allowedOperations().contains("customer.read.all"));
            return new CustomerProfileVerifyResponse(request.request().requestId(),
                    CustomerProfileVerifyResponse.VERIFIED, 55L, "OWNER", null, 1_800_000_000L);
        });
        var client = client(rpc, SECRET);
        try (var ignored = ListingAuthzScopeFixture.openWithExecution(true,
                List.of("customer.read.all"), List.of("customer.read.all"), Map.of(),
                "run-1", "session-1")) {
            assertEquals(55L, client.verifyOwnerProfile(55L).customerId());
        }
    }

    @Test
    void nonVerifiedResultsAndDependencyFailuresFailClosed() {
        for (String result : List.of(CustomerProfileVerifyResponse.NOT_FOUND,
                CustomerProfileVerifyResponse.NOT_ACCESSIBLE,
                CustomerProfileVerifyResponse.INVALID_PROFILE_TYPE)) {
            var rpc = mock(CustomerProfileVerifyRpcService.class);
            when(rpc.verifyOwnerProfileContext(any(), any())).thenAnswer(call -> {
                var request = (RagContextAuthorizedCustomerProfileVerifyRequest) call.getArgument(0);
                return new CustomerProfileVerifyResponse(request.request().requestId(), result,
                        null, null, null, null);
            });
            var client = client(rpc, SECRET);
            try (var ignored = ListingAuthzScopeFixture.openWithExecution(false,
                    List.of("customer.read"), List.of("customer.read"), Map.of("BROKER", 9L),
                    "run-1", "session-1")) {
                assertThrows(RagException.class, () -> client.verifyOwnerProfile(55L));
            }
        }
        var rpcFailure = mock(CustomerProfileVerifyRpcService.class);
        when(rpcFailure.verifyOwnerProfileContext(any(), any()))
                .thenThrow(new RuntimeException("dependency down"));
        var client = client(rpcFailure, SECRET);
        try (var ignored = ListingAuthzScopeFixture.openWithExecution(false,
                List.of("customer.read"), List.of("customer.read"), Map.of("BROKER", 9L),
                "run-1", "session-1")) {
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(RagException.class, () -> client.verifyOwnerProfile(55L)).code());
        }
    }

    @Test
    void requestIdMismatchOrMissingPermissionFailsClosed() {
        var rpc = mock(CustomerProfileVerifyRpcService.class);
        when(rpc.verifyOwnerProfileContext(any(), any())).thenAnswer(call -> {
            var request = (RagContextAuthorizedCustomerProfileVerifyRequest) call.getArgument(0);
            return new CustomerProfileVerifyResponse("different-request-id",
                    CustomerProfileVerifyResponse.VERIFIED, 55L, "OWNER", 9L, 1_800_000_000L);
        });
        var client = client(rpc, SECRET);
        try (var ignored = ListingAuthzScopeFixture.openWithExecution(false,
                List.of("customer.read"), List.of("customer.read"), Map.of("BROKER", 9L),
                "run-1", "session-1")) {
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(RagException.class, () -> client.verifyOwnerProfile(55L)).code());
        }
        var noPermissionRpc = mock(CustomerProfileVerifyRpcService.class);
        var noPermission = client(noPermissionRpc, SECRET);
        try (var ignored = ListingAuthzScopeFixture.openWithExecution(false,
                List.of("listing.create"), List.of("listing.create"), Map.of("BROKER", 9L),
                "run-1", "session-1")) {
            // 缺少 customer.read 权限时失败关闭（AuthzException 被翻译为 RagException）。
            assertEquals(RagErrorCode.FORBIDDEN,
                    assertThrows(RagException.class, () -> noPermission.verifyOwnerProfile(55L)).code());
        }
        verifyNoInteractions(noPermissionRpc);
    }

    @Test
    void contextWithoutExecutionBindingFailsClosed() {
        var rpc = mock(CustomerProfileVerifyRpcService.class);
        var client = client(rpc, SECRET);
        try (var ignored = ListingAuthzScopeFixture.open(false,
                List.of("customer.read"), List.of("customer.read"), Map.of("BROKER", 9L))) {
            // 契约要求 executionBinding（runId/sessionId）；普通 HTTP 上下文缺失绑定时失败关闭。
            assertThrows(RagException.class, () -> client.verifyOwnerProfile(55L));
        }
        verifyNoInteractions(rpc);
    }
}
