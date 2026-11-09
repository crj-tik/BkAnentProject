package com.bkanent.customer.service;

import com.bkanent.common.a2a.VerifiedDomainAuthorization;
import com.bkanent.common.rag.RagActorMode;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedListingInquiryVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagListingInquiryVerifyRequest;
import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.rag.RagRequestSigner;
import com.bkanent.common.rpc.ListingInquiryVerifyResponse;
import com.bkanent.common.rpc.ListingInquiryVerifyRpcService;
import com.bkanent.customer.rpc.CustomerVerifyContractHash;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Customer→Listing 咨询房源验证签名 RPC 调用方，参照营销侧 CampaignListingSnapshotClient：
 * 复用当前已验证的用户授权上下文，转发签名上下文并对请求做服务级 HMAC 签名；
 * 密钥未配置、依赖不可用或响应异常时失败关闭（DEPENDENCY_UNAVAILABLE 语义），
 * 不以请求字段推断身份。
 */
@Component
public class ListingInquiryVerifyClient {

    /**
     * 目标服务受众。
     */
    public static final String LISTING_SERVICE = "listing-master-service";

    /**
     * 本服务调用方名称。
     */
    public static final String CALLER_SERVICE = "customer-service";

    @DubboReference(check = false, parameters = {"serialization=fastjson2ctor"})
    private ListingInquiryVerifyRpcService listings;

    private final String keyId;
    private final String secret;

    public ListingInquiryVerifyClient(
            @Value("${customer.listing-inquiry-verify.key-id:customer-inquiry-verify-current}") String keyId,
            @Value("${customer.listing-inquiry-verify.signing-secret:}") String secret) {
        this.keyId = keyId;
        this.secret = secret;
    }

    /**
     * 验证咨询目标房源当前可咨询（在售且当前操作人具备 listing.read 范围）。
     * 返回 ACCESSIBLE/NOT_ACCESSIBLE 业务结果；验签或依赖失败抛出异常。
     */
    public ListingInquiryVerifyResponse verify(Long listingId) {
        if (listingId == null || listingId <= 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "listingId");
        }
        if (listings == null) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        VerifiedDomainAuthorization.VerifiedContext verified = VerifiedDomainAuthorization.requireVerifiedContext();
        String operation = verified.context().allowedOperations().contains("listing.read.all")
                ? "listing.read.all" : "listing.read";
        VerifiedDomainAuthorization.requirePermission(operation);
        String requestId = UUID.randomUUID().toString();
        RagContextAuthorizedListingInquiryVerifyRequest request = new RagContextAuthorizedListingInquiryVerifyRequest(
                1, new RagListingInquiryVerifyRequest(1, requestId, listingId),
                verified.context(), verified.freshness());
        long now = Instant.now().getEpochSecond();
        long expires = Math.min(now + 60, Math.min(verified.context().expiresAtEpochSecond(),
                Math.min(verified.context().runHardDeadlineEpochSecond(),
                        verified.freshness().notAfterEpochSecond())));
        if (expires <= now) {
            throw new RagException(RagErrorCode.AUTHENTICATION_EXPIRED);
        }
        RagAuthEnvelope unsigned = new RagAuthEnvelope(1, keyId, LISTING_SERVICE, CALLER_SERVICE,
                RagOperation.READ, requestId, CustomerVerifyContractHash.listingInquiryVerifyRequestHash(request),
                RagActorMode.USER, verified.actorId(), now, expires, null, null);
        RagAuthEnvelope envelope = RagRequestSigner.sign(unsigned, secret.getBytes(StandardCharsets.UTF_8));
        ListingInquiryVerifyResponse response;
        try {
            response = listings.verifyInquiryListingContext(request, envelope);
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
