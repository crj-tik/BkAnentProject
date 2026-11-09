package com.bkanent.listing.rpc;

import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedListingInquiryVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rpc.ListingInquiryVerifyResponse;
import com.bkanent.common.rpc.ListingInquiryVerifyRpcService;
import com.bkanent.listing.config.ListingAuthorizationProperties;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.rag.ListingRagIdentity;
import com.bkanent.listing.service.ListingDomainService;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ListingInquiryVerifyRpcServiceImpl 咨询房源验证 RPC 提供方（customer-service→listing-master-service）。
 * 按签名上下文核验：房源存在、未删除、状态为 ON_SALE、当前操作人具备 listing.read 范围；
 * 不可访问时统一返回 NOT_ACCESSIBLE，不区分不存在与无权，避免泄露房源事实。
 */
@DubboService(serialization = "fastjson2ctor", preferSerialization = "fastjson2ctor")
public class ListingInquiryVerifyRpcServiceImpl implements ListingInquiryVerifyRpcService {

    /**
     * 字段：identity。
     */
    private final ListingRagIdentity identity;
    /**
     * 字段：domain。
     */
    private final ListingDomainService domain;
    /**
     * 字段：authorizationProperties。
     */
    private final ListingAuthorizationProperties authorizationProperties;

    /**
     * 构造 ListingInquiryVerifyRpcServiceImpl 实例。
     */
    @Autowired
    public ListingInquiryVerifyRpcServiceImpl(ListingRagIdentity identity,
                                              ListingDomainService domain,
                                              ListingAuthorizationProperties authorizationProperties) {
        this.identity = identity;
        this.domain = domain;
        this.authorizationProperties = authorizationProperties;
    }

    /**
     * 咨询房源验证：版本化入口未启用时拒绝；验签失败抛出 RagException；
     * 房源不可读统一 NOT_ACCESSIBLE，可读返回状态与行版本用于提交后复查。
     */
    @Override
    public ListingInquiryVerifyResponse verifyInquiryListingContext(
            RagContextAuthorizedListingInquiryVerifyRequest request, RagAuthEnvelope authentication) {
        if (!authorizationProperties.isEnabled()) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        var principal = identity.verifyAuthorizedInquiryVerifyContext(request, authentication,
                authorizationProperties.decodedAuthorityPublicKeys());
        String requestId = request.request().requestId();
        ListingEntity listing = domain.getById(request.request().listingId());
        if (listing == null || !Integer.valueOf(0).equals(listing.getDeleted())
                || !"ON_SALE".equals(listing.getStatus())
                || !ListingRagIdentity.canRead(principal, listing)) {
            return new ListingInquiryVerifyResponse(requestId, ListingInquiryVerifyResponse.NOT_ACCESSIBLE,
                    null, null, null);
        }
        long version = listing.getVersion() == null ? 0L : listing.getVersion();
        return new ListingInquiryVerifyResponse(requestId, ListingInquiryVerifyResponse.ACCESSIBLE,
                listing.getId(), listing.getStatus(), String.valueOf(version));
    }
}
