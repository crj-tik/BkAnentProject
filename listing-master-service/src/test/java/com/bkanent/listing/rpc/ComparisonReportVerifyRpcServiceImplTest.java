package com.bkanent.listing.rpc;

import com.bkanent.common.authz.AuthzCanonicalHash;
import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingMoney;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotFacts;
import com.bkanent.common.listing.ListingSnapshotFactory;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagComparisonReportVerifyRequest;
import com.bkanent.common.rag.RagContextAuthorizedComparisonReportVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rpc.ListingComparisonReportVerifyResponse;
import com.bkanent.common.security.ServiceIdentityCrypto;
import com.bkanent.listing.comparison.ListingComparisonReportStore;
import com.bkanent.listing.comparison.config.ListingComparisonProperties;
import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;
import com.bkanent.listing.comparison.ListingComparisonPdfService;
import com.bkanent.listing.config.ListingAuthorizationProperties;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.rag.ListingRagIdentity;
import com.bkanent.listing.rag.ListingVerifyContractHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 对比报告归属验证 RPC：本人报告 ACCESSIBLE；缺失/非本人统一 NOT_ACCESSIBLE；验签失败拒绝。 */
class ComparisonReportVerifyRpcServiceImplTest {

    /** 测试密钥用生成值，不写字面量密钥。 */
    private static final String SECRET = ("comparison-verify-" + UUID.randomUUID()).replace("-", "");
    private static final String KEY_ID = "customer-comparison-verify-current";
    private static final String AUTH_KEY_ID = "auth-comparison-test";
    private static final String REPORT_ID = "ab".repeat(16);
    private static final List<ListingSourceSnapshot> SOURCES = List.of(
            snapshot("legacy-local", "101", "rev-101"),
            snapshot("legacy-local", "102", "rev-102"));

    @TempDir
    Path directory;

    private ListingRagIdentity identity() {
        var properties = new ListingRagProperties();
        var caller = new ListingRagProperties.TrustedCaller();
        caller.setCallerService("customer-service");
        caller.setSecret(SECRET);
        properties.setTrustedCallers(Map.of(KEY_ID, caller));
        return new ListingRagIdentity(properties,
                new StaticListableBeanFactoryProvider().provider());
    }

    private record Execution(AuthorizationContext context, AuthzFreshness freshness) {
    }

    /** audience 可选：agent-service（A2A 运行）或 customer-service（网关入口绑定，双收）。 */
    private Execution execution(KeyPair authority, List<String> operations, String audience) {
        long now = Instant.now().getEpochSecond();
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String hash = "d".repeat(64);
        var context = AuthzSignature.sign(new AuthorizationContext("42", 10, 1, hash, operations,
                audience, "parent-request", "run-1", "session-1", "e".repeat(64),
                "f".repeat(64), nonce, now, now + 60, now + 300, Map.of("BROKER", 84L),
                AuthzContract.ALGORITHM, AUTH_KEY_ID, null), authority.getPrivate());
        var freshness = AuthzSignature.sign(new AuthzFreshness("42", 10, 1, AuthorizationStatus.ACTIVE,
                hash, now, now + 30, nonce, AuthzContract.ALGORITHM, AUTH_KEY_ID, null), authority.getPrivate());
        return new Execution(context, freshness);
    }

    private RagAuthEnvelope sign(RagContextAuthorizedComparisonReportVerifyRequest request, String actorId) {
        long now = Instant.now().getEpochSecond();
        long expiry = Math.min(now + 30, request.freshness().notAfterEpochSecond());
        return RagRequestSigner.sign(new RagAuthEnvelope(1, KEY_ID, ListingRagIdentity.SERVICE,
                "customer-service", RagOperation.READ, request.request().requestId(),
                ListingVerifyContractHash.comparisonReportVerifyRequestHash(request), RagActorMode.USER, actorId,
                now, expiry, null, null), SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private ListingComparisonReportVerifyRpcServiceImpl rpc(KeyPair authority, ListingComparisonReportStore store,
                                                            boolean enabled) {
        var properties = new ListingAuthorizationProperties();
        properties.setEnabled(enabled);
        properties.setAuthorityPublicKeys(Map.of(AUTH_KEY_ID,
                ServiceIdentityCrypto.encodePublicKey(authority.getPublic())));
        return new ListingComparisonReportVerifyRpcServiceImpl(identity(), store, properties);
    }

    private ListingComparisonReportStore store() {
        var properties = new ListingComparisonProperties();
        properties.setStorageDir(directory.toString());
        var store = new ListingComparisonReportStore(new ObjectMapper().findAndRegisterModules(), properties,
                new ListingComparisonPdfService());
        store.initialize();
        return store;
    }

    private static ListingComparisonReportResponse report(String reportId) {
        return new ListingComparisonReportResponse(reportId, "domain-parallel-v1", "listing-comparison-v1",
                Instant.now(), ListingDataMode.UNKNOWN, SOURCES, List.of(), List.of(), "| 来源 |\n", "测试结论",
                "/listings/comparisons/" + reportId + "/pdf");
    }

    private static ListingSourceSnapshot snapshot(String system, String id, String revision) {
        return ListingSnapshotFactory.create(new ListingRef(system, id), revision,
                Instant.parse("2026-11-06T00:00:00Z"), ListingDataMode.UNKNOWN, system + "-dataset",
                new ListingSnapshotFacts("测试房源 " + id, "100",
                        new ListingMoney("3200000", "CNY", ListingMoney.Unit.YUAN), Map.of(), List.of()));
    }

    /** 与 ListingComparisonFingerprint.sourceVersionHash 相同的 v1 规范编码（包私有，测试内独立复刻）。 */
    private static String sourceVersionHash(List<ListingSourceSnapshot> sources) {
        String canonical = sources.stream()
                .sorted(Comparator.comparing((ListingSourceSnapshot item) -> item.listingRef().sourceSystem())
                        .thenComparing(item -> item.listingRef().sourceId()))
                .map(item -> item.listingRef().sourceSystem() + "\u0000" + item.listingRef().sourceId()
                        + "\u0000" + item.revision() + "\u0000" + item.contentHash())
                .reduce((left, right) -> left + "\u0001" + right).orElse("");
        return sha256("listing-source-v1\u0000" + canonical);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private RagContextAuthorizedComparisonReportVerifyRequest request(Execution execution, String requestId,
                                                                      String reportId) {
        return new RagContextAuthorizedComparisonReportVerifyRequest(1,
                new RagComparisonReportVerifyRequest(1, requestId, reportId),
                execution.context(), execution.freshness());
    }

    @Test
    void ownedReportIsAccessibleWithReportIdEcho() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var store = store();
        store.createIfAbsent("42", "c".repeat(64), sourceVersionHash(SOURCES), report(REPORT_ID));
        var execution = execution(authority, List.of("compare.report.read.own"), "agent-service");
        var signedRequest = request(execution, "comparison-verify-1", REPORT_ID);
        var response = rpc(authority, store, true).verifyReportOwnerContext(signedRequest, sign(signedRequest, "42"));
        assertEquals(ListingComparisonReportVerifyResponse.ACCESSIBLE, response.result());
        assertEquals("comparison-verify-1", response.requestId());
        assertEquals(REPORT_ID, response.reportId());
    }

    @Test
    void gatewayBoundCustomerAudienceIsAlsoAccepted() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var store = store();
        store.createIfAbsent("42", "c".repeat(64), sourceVersionHash(SOURCES), report(REPORT_ID));
        // 网关入口绑定上下文（受众 customer-service）与 A2A 运行上下文（受众 agent-service）双收。
        var execution = execution(authority, List.of("compare.report.read.own"), "customer-service");
        var signedRequest = request(execution, "comparison-verify-2", REPORT_ID);
        var response = rpc(authority, store, true).verifyReportOwnerContext(signedRequest, sign(signedRequest, "42"));
        assertEquals(ListingComparisonReportVerifyResponse.ACCESSIBLE, response.result());
    }

    @Test
    void missingReportIsNotAccessibleWithoutReportId() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var store = store();
        var execution = execution(authority, List.of("compare.report.read.own"), "agent-service");
        var signedRequest = request(execution, "comparison-verify-3", "cd".repeat(16));
        var response = rpc(authority, store, true).verifyReportOwnerContext(signedRequest, sign(signedRequest, "42"));
        assertEquals(ListingComparisonReportVerifyResponse.NOT_ACCESSIBLE, response.result());
        assertEquals("comparison-verify-3", response.requestId());
        assertNull(response.reportId());
    }

    @Test
    void foreignOwnedReportIsNotAccessible() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var store = store();
        // 报告 owner 是另一位经纪人（77），当前操作人 42 不可读，统一 NOT_ACCESSIBLE 不区分原因。
        store.createIfAbsent("77", "c".repeat(64), sourceVersionHash(SOURCES), report(REPORT_ID));
        var execution = execution(authority, List.of("compare.report.read.own"), "agent-service");
        var signedRequest = request(execution, "comparison-verify-4", REPORT_ID);
        var response = rpc(authority, store, true).verifyReportOwnerContext(signedRequest, sign(signedRequest, "42"));
        assertEquals(ListingComparisonReportVerifyResponse.NOT_ACCESSIBLE, response.result());
        assertNull(response.reportId());
    }

    @Test
    void disabledAuthorizationOrTamperedProofOrMissingOperationRejects() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var execution = execution(authority, List.of("compare.report.read.own"), "agent-service");
        var signedRequest = request(execution, "comparison-verify-5", REPORT_ID);
        var store = mock(ListingComparisonReportStore.class);
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class,
                () -> rpc(authority, store, false).verifyReportOwnerContext(signedRequest,
                        sign(signedRequest, "42"))).code());
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class,
                () -> rpc(authority, store, true).verifyReportOwnerContext(signedRequest,
                        sign(signedRequest, "43"))).code());
        var restricted = execution(authority, List.of("customer.match.own"), "agent-service");
        var noComparePermission = request(restricted, "comparison-verify-5", REPORT_ID);
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class,
                () -> rpc(authority, store, true).verifyReportOwnerContext(noComparePermission,
                        sign(noComparePermission, "42"))).code());
        verifyNoInteractions(store);
    }

    @Test
    void contractHashEncodingMatchesTheCustomerSideIndependentEncoding() throws Exception {
        var authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var execution = execution(authority, List.of("compare.report.read.own"), "agent-service");
        var signedRequest = request(execution, "comparison-verify-6", REPORT_ID);
        // 独立复刻 customer 侧 CustomerVerifyContractHash 编码（域分隔 + 长度前缀 + 类型标记），
        // 防止两侧实现漂移（KI-249）。
        assertEquals(independentComparisonHash(signedRequest),
                ListingVerifyContractHash.comparisonReportVerifyRequestHash(signedRequest));
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

    /** StaticListableBeanFactory 需要 getBeanProvider；此处仅复刻 ListingInquiryVerifyRpcServiceImplTest 的空提供方。 */
    private static final class StaticListableBeanFactoryProvider {
        org.springframework.beans.factory.support.StaticListableBeanFactory factory =
                new org.springframework.beans.factory.support.StaticListableBeanFactory();

        org.springframework.beans.factory.ObjectProvider<com.bkanent.common.rpc.RagPrincipalRpcService> provider() {
            return factory.getBeanProvider(com.bkanent.common.rpc.RagPrincipalRpcService.class);
        }
    }
}
