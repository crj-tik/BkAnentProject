package com.bkanent.customer.service;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagComparisonReportVerifyRequest;
import com.bkanent.common.rag.RagContextAuthorizedComparisonReportVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rpc.ListingComparisonReportVerifyResponse;
import com.bkanent.common.rpc.ListingComparisonReportVerifyRpcService;
import com.bkanent.customer.rpc.CustomerVerifyContractHash;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Customer→Listing 对比报告归属验证签名 RPC 调用方，参照 {@link ListingInquiryVerifyClient}：
 * 复用当前已验证的用户授权上下文，转发签名上下文并对请求做服务级 HMAC 签名；
 * 密钥未配置、依赖不可用或响应异常时失败关闭（DEPENDENCY_UNAVAILABLE 语义），
 * 不以请求字段推断身份。咨询-对比报告关联（attach）在落库前必须先经此核验。
 */
@Component
public class ListingComparisonReportVerifyClient {

    /**
     * 目标服务受众。
     */
    public static final String LISTING_SERVICE = "listing-master-service";

    /**
     * 本服务调用方名称。
     */
    public static final String CALLER_SERVICE = "customer-service";

    @DubboReference(check = false)
    private ListingComparisonReportVerifyRpcService listings;

    private final String keyId;
    private final String secret;

    public ListingComparisonReportVerifyClient(
            @Value("${customer.comparison-report-verify.key-id:customer-comparison-verify-current}") String keyId,
            @Value("${customer.comparison-report-verify.signing-secret:}") String secret) {
        this.keyId = keyId;
        this.secret = secret;
    }

    /**
     * 验证对比报告为当前操作人所有且可读（compare.report.read.own）。
     * 返回 ACCESSIBLE/NOT_ACCESSIBLE 业务结果；验签或依赖失败抛出异常。
     */
    public ListingComparisonReportVerifyResponse verify(String reportId) {
        if (reportId == null || !reportId.matches("[0-9a-f]{32}")) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "reportId");
        }
        if (listings == null) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        VerifiedDomainAuthorization.VerifiedContext verified = VerifiedDomainAuthorization.requireVerifiedContext();
        // 对比报告读取只有 own 一档（own=all=该操作），无宽域变体。
        VerifiedDomainAuthorization.requirePermission("compare.report.read.own");
        String requestId = UUID.randomUUID().toString();
        RagContextAuthorizedComparisonReportVerifyRequest request =
                new RagContextAuthorizedComparisonReportVerifyRequest(
                        1, new RagComparisonReportVerifyRequest(1, requestId, reportId),
                        verified.context(), verified.freshness());
        long now = Instant.now().getEpochSecond();
        long expires = Math.min(now + 60, Math.min(verified.context().expiresAtEpochSecond(),
                Math.min(verified.context().runHardDeadlineEpochSecond(),
                        verified.freshness().notAfterEpochSecond())));
        if (expires <= now) {
            throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        }
        RagAuthEnvelope unsigned = new RagAuthEnvelope(1, keyId, LISTING_SERVICE, CALLER_SERVICE,
                RagOperation.READ, requestId, CustomerVerifyContractHash.comparisonReportVerifyRequestHash(request),
                RagActorMode.USER, verified.actorId(), now, expires, null, null);
        RagAuthEnvelope envelope = RagRequestSigner.sign(unsigned, secret.getBytes(StandardCharsets.UTF_8));
        ListingComparisonReportVerifyResponse response;
        try {
            response = listings.verifyReportOwnerContext(request, envelope);
        } catch (RagException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (response == null || !requestId.equals(response.requestId())) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return response;
    }
}
