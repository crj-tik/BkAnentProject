package com.bkanent.customer.service;

import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagComparisonReportVerifyRequest;
import com.bkanent.common.rag.RagContextAuthorizedComparisonReportVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rag.RagSigningKey;
import com.bkanent.common.rpc.ListingComparisonReportVerifyResponse;
import com.bkanent.common.rpc.ListingComparisonReportVerifyRpcService;
import com.bkanent.customer.rpc.CustomerVerifyContractHash;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Customer→Listing 对比报告归属验证客户端测试：签名可验证、密钥缺失/依赖异常失败关闭、
 * 哈希编码与 listing 侧独立实现一致（KI-249 防漂移）。
 */
class ListingComparisonReportVerifyClientTest {

    /** 测试密钥用生成值，不写字面量密钥。 */
    private static final String SECRET = ("comparison-verify-" + UUID.randomUUID()).replace("-", "");
    private static final String KEY_ID = "customer-comparison-verify";
    private static final String REPORT_ID = "ab".repeat(16);

    private final ListingComparisonReportVerifyRpcService listings = mock(ListingComparisonReportVerifyRpcService.class);

    private ListingComparisonReportVerifyClient client(String secret) {
        ListingComparisonReportVerifyClient client = new ListingComparisonReportVerifyClient(KEY_ID, secret);
        ReflectionTestUtils.setField(client, "listings", listings);
        return client;
    }

    @Test
    void signedBoundContextProducesVerifiableEnvelopeAndReturnsResponse() {
        ListingComparisonReportVerifyClient client = client(SECRET);
        when(listings.verifyReportOwnerContext(any(), any())).thenAnswer(call -> {
            RagContextAuthorizedComparisonReportVerifyRequest request = call.getArgument(0);
            RagAuthEnvelope envelope = call.getArgument(1);
            RagRequestSigner.verify(envelope,
                    CustomerVerifyContractHash.comparisonReportVerifyRequestHash(request),
                    "listing-master-service", RagOperation.READ, request.request().requestId(), null,
                    new RagSigningKey(KEY_ID, "customer-service", null, Set.of(RagOperation.READ),
                            SECRET.getBytes(StandardCharsets.UTF_8)), Instant.now());
            assertEquals(REPORT_ID, request.request().reportId());
            assertEquals(RagActorMode.USER, envelope.actorMode());
            assertEquals("123", envelope.actorId());
            return new ListingComparisonReportVerifyResponse(request.request().requestId(),
                    ListingComparisonReportVerifyResponse.ACCESSIBLE, REPORT_ID);
        });

        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own", "compare.report.read.own"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            ListingComparisonReportVerifyResponse response = client.verify(REPORT_ID);
            assertEquals(ListingComparisonReportVerifyResponse.ACCESSIBLE, response.result());
            assertEquals(REPORT_ID, response.reportId());
        }
        verify(listings).verifyReportOwnerContext(any(), any());
    }

    @Test
    void missingSecretFailsClosedWithoutCallingListing() {
        ListingComparisonReportVerifyClient client = client("");
        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own", "compare.report.read.own"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            RagException failure = assertThrows(RagException.class, () -> client.verify(REPORT_ID));
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, failure.code());
        }
        verifyNoInteractions(listings);
    }

    @Test
    void nullOrMismatchedResponseFailsClosed() {
        ListingComparisonReportVerifyClient client = client(SECRET);
        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own", "compare.report.read.own"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            when(listings.verifyReportOwnerContext(any(), any())).thenReturn(null);
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(RagException.class, () -> client.verify(REPORT_ID)).code());

            when(listings.verifyReportOwnerContext(any(), any())).thenReturn(
                    new ListingComparisonReportVerifyResponse("other-request",
                            ListingComparisonReportVerifyResponse.ACCESSIBLE, REPORT_ID));
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(RagException.class, () -> client.verify(REPORT_ID)).code());
        }
    }

    @Test
    void rpcFailureMapsToDependencyUnavailable() {
        ListingComparisonReportVerifyClient client = client(SECRET);
        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own", "compare.report.read.own"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            when(listings.verifyReportOwnerContext(any(), any()))
                    .thenThrow(new IllegalStateException("private"));
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                    assertThrows(RagException.class, () -> client.verify(REPORT_ID)).code());
        }
    }

    @Test
    void missingCompareReportReadPermissionFailsClosedBeforeCallingListing() {
        ListingComparisonReportVerifyClient client = client(SECRET);
        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            assertThrows(AuthzException.class, () -> client.verify(REPORT_ID));
        }
        verifyNoInteractions(listings);
    }

    @Test
    void invalidReportIdFailsClosedBeforeCallingListing() {
        ListingComparisonReportVerifyClient client = client(SECRET);
        try (var ignored = CustomerAuthzScopeFixture.openBound(false,
                List.of("customer.inquiry.write.own", "compare.report.read.own"), Map.of("BROKER", 41L),
                "run-1", "session-1")) {
            assertEquals(RagErrorCode.INVALID_ARGUMENT,
                    assertThrows(RagException.class, () -> client.verify(null)).code());
            assertEquals(RagErrorCode.INVALID_ARGUMENT,
                    assertThrows(RagException.class, () -> client.verify("not-hex")).code());
            assertEquals(RagErrorCode.INVALID_ARGUMENT,
                    assertThrows(RagException.class, () -> client.verify("AB".repeat(16))).code());
        }
        verifyNoInteractions(listings);
    }

    @Test
    void contractHashEncodingMatchesTheListingSideIndependentEncoding() throws Exception {
        // 独立复刻 listing 侧 ListingVerifyContractHash 编码（域分隔 + 长度前缀 + 类型标记），
        // 防止两侧实现漂移（KI-249）。
        var authority = ed25519();
        long now = Instant.now().getEpochSecond();
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var context = com.bkanent.common.authz.AuthzSignature.sign(
                new com.bkanent.common.authz.AuthorizationContext("123", 1, 0, "d".repeat(64),
                        List.of("compare.report.read.own"), "customer-service", "request-1", "run-1", "session-1",
                        "e".repeat(64), "f".repeat(64), nonce, now, now + 60, now + 300,
                        Map.of("BROKER", 41L), com.bkanent.common.authz.AuthzContract.ALGORITHM,
                        "auth-comparison-test", null), authority.getPrivate());
        var freshness = com.bkanent.common.authz.AuthzSignature.sign(
                new com.bkanent.common.authz.AuthzFreshness("123", 1, 0,
                        com.bkanent.common.authz.AuthorizationStatus.ACTIVE, "d".repeat(64),
                        now, now + 30, nonce,
                        com.bkanent.common.authz.AuthzContract.ALGORITHM, "auth-comparison-test", null),
                authority.getPrivate());
        var request = new RagContextAuthorizedComparisonReportVerifyRequest(1,
                new RagComparisonReportVerifyRequest(1, "comparison-verify-7", REPORT_ID),
                context, freshness);
        assertEquals(independentComparisonHash(request),
                CustomerVerifyContractHash.comparisonReportVerifyRequestHash(request));
    }

    private static java.security.KeyPair ed25519() {
        try {
            return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String independentComparisonHash(
            RagContextAuthorizedComparisonReportVerifyRequest request) throws Exception {
        return independentDigest("rag.context-authorized-comparison-report-verify.v1",
                request.request().contractVersion(), request.request().requestId(), request.request().reportId(),
                AuthzCanonicalHash.signedContextHash(request.authorizationContext()),
                AuthzCanonicalHash.signedFreshnessHash(request.freshness()));
    }

    private static String independentDigest(String domain, Object... fields) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            byte[] encoded = domain.getBytes(StandardCharsets.UTF_8);
            output.writeInt(encoded.length);
            output.write(encoded);
            for (Object field : fields) {
                if (field instanceof Integer value) {
                    output.writeByte(2);
                    output.writeInt(value);
                } else if (field instanceof Long value) {
                    output.writeByte(3);
                    output.writeLong(value);
                } else {
                    output.writeByte(1);
                    byte[] text = ((String) field).getBytes(StandardCharsets.UTF_8);
                    output.writeInt(text.length);
                    output.write(text);
                }
            }
        }
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }
}
