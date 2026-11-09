package com.bkanent.listing.customer;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedCustomerProfileVerifyRequest;
import com.bkanent.common.rag.RagCustomerProfileVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rpc.CustomerProfileVerifyResponse;
import com.bkanent.common.rpc.CustomerProfileVerifyRpcService;
import com.bkanent.listing.rag.ListingVerifyContractHash;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * CustomerProfileVerifyClient 业主档案验证调用方（listing-master-service→customer-service）。
 * 参照 {@code CampaignListingSnapshotClient} 的 context-authorized 模式：复用既有
 * RagAuthEnvelope 服务签名加用户 Auth 上下文转发，不新增认证协议；
 * 档案不可访问、类型不符或依赖不可用时失败关闭，不以请求字段推断归属。
 */
@Component
public class CustomerProfileVerifyClient {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CustomerProfileVerifyClient.class);

    /**
     * 常量：AUDIENCE（目标服务）。
     */
    private static final String AUDIENCE = "customer-service";
    /**
     * 常量：CALLER（调用方服务身份）。
     */
    private static final String CALLER = "listing-master-service";
    /**
     * 常量：EXPECTED_PROFILE_TYPE（卖方档案类型）。
     */
    private static final String EXPECTED_PROFILE_TYPE = "OWNER";

    @DubboReference(check = false)
    private CustomerProfileVerifyRpcService customerProfileVerify;
    /**
     * 字段：keyId（服务签名 keyId）。
     */
    @Value("${listing.customer-verify.key-id:customer-verify-current}")
    private String keyId = "customer-verify-current";
    /**
     * 字段：secret（服务签名密钥，至少 32 字节）。
     */
    @Value("${listing.customer-verify.signing-secret:}")
    private String secret = "";

    /**
     * 验证业主档案：当前已验签用户上下文 + 服务身份签名调用 Customer 受权档案验证。
     * 仅 VERIFIED 返回摘要；其余结果与依赖异常均明确失败。
     */
    public VerifiedOwnerProfile verifyOwnerProfile(long customerId) {
        if (customerProfileVerify == null) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (!StringUtils.hasText(secret) || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            var verified = VerifiedDomainAuthorization.requireVerifiedContext();
            String operation = verified.context().allowedOperations().contains("customer.read.all")
                    ? "customer.read.all" : "customer.read";
            VerifiedDomainAuthorization.requirePermission(operation);
            String requestId = UUID.randomUUID().toString();
            var request = new RagContextAuthorizedCustomerProfileVerifyRequest(1,
                    new RagCustomerProfileVerifyRequest(1, requestId, customerId, EXPECTED_PROFILE_TYPE),
                    verified.context(), verified.freshness());
            long now = Instant.now().getEpochSecond();
            long expires = Math.min(now + 60, Math.min(verified.context().expiresAtEpochSecond(),
                    Math.min(verified.context().runHardDeadlineEpochSecond(),
                            verified.freshness().notAfterEpochSecond())));
            if (expires <= now) {
                throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
            }
            var unsigned = new RagAuthEnvelope(1, keyId, AUDIENCE, CALLER, RagOperation.READ, requestId,
                    ListingVerifyContractHash.customerProfileVerifyRequestHash(request), RagActorMode.USER,
                    verified.actorId(), now, expires, null, null);
            var envelope = RagRequestSigner.sign(unsigned, secret.getBytes(StandardCharsets.UTF_8));
            CustomerProfileVerifyResponse response;
            try {
                response = customerProfileVerify.verifyOwnerProfileContext(request, envelope);
            } catch (RagException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            if (response == null || response.requestId() == null || !response.requestId().equals(requestId)) {
                throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            }
            if (!CustomerProfileVerifyResponse.VERIFIED.equals(response.result())) {
                throw translate(response.result());
            }
            return new VerifiedOwnerProfile(response.customerId(), response.profileType(),
                    response.brokerId(), response.updatedAtEpochSecond());
        } catch (RagException failure) {
            throw failure;
        } catch (AuthzException failure) {
            log.warn("业主档案验证授权失败：{}", failure.getMessage());
            throw new RagException(switch (failure.code()) {
                case EXPIRED -> RagErrorCode.AUTHENTICATION_EXPIRED;
                case PERMISSION_DENIED -> RagErrorCode.FORBIDDEN;
                default -> RagErrorCode.AUTHENTICATION_FAILED;
            });
        } catch (Exception failure) {
            log.warn("业主档案验证依赖失败：{}", failure.toString(), failure);
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * 非 VERIFIED 结果映射为明确的失败关闭错误，不区分档案缺失与无权的内部细节。
     */
    private static RagException translate(String result) {
        if (CustomerProfileVerifyResponse.NOT_FOUND.equals(result)) {
            return new RagException(RagErrorCode.NOT_FOUND, "ownerProfile");
        }
        if (CustomerProfileVerifyResponse.NOT_ACCESSIBLE.equals(result)) {
            return new RagException(RagErrorCode.FORBIDDEN, "ownerProfile");
        }
        if (CustomerProfileVerifyResponse.INVALID_PROFILE_TYPE.equals(result)) {
            return new RagException(RagErrorCode.INVALID_ARGUMENT, "ownerProfile");
        }
        return new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "ownerProfile");
    }

    /**
     * 已验证业主档案摘要（id、类型、经纪人、更新时间）。
     */
    public record VerifiedOwnerProfile(Long customerId, String profileType, Long brokerId,
                                       Long updatedAtEpochSecond) {
    }
}
