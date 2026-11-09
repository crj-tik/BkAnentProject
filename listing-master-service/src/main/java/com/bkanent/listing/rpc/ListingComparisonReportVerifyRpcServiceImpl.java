package com.bkanent.listing.rpc;

import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedComparisonReportVerifyRequest;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rpc.ListingComparisonReportVerifyResponse;
import com.bkanent.common.rpc.ListingComparisonReportVerifyRpcService;
import com.bkanent.listing.comparison.ListingComparisonReportStore;
import com.bkanent.listing.config.ListingAuthorizationProperties;
import com.bkanent.listing.rag.ListingRagIdentity;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ListingComparisonReportVerifyRpcServiceImpl 对比报告归属验证 RPC 提供方
 * （customer-service→listing-master-service，咨询-对比报告关联前置核验）。
 * 按签名上下文核验：当前操作人具备 compare.report.read.own（own=all=该操作，无宽域变体）
 * 且报告存在、owner 为当前操作人；报告不存在或非本人所有统一返回 NOT_ACCESSIBLE，
 * 不区分二者，避免泄露报告存在性（设计决策 3：attach 时核验一次，读取不重复核验）。
 */
@DubboService(serialization = "fastjson2ctor", preferSerialization = "fastjson2ctor")
public class ListingComparisonReportVerifyRpcServiceImpl implements ListingComparisonReportVerifyRpcService {

    /**
     * 字段：identity。
     */
    private final ListingRagIdentity identity;
    /**
     * 字段：store。
     */
    private final ListingComparisonReportStore store;
    /**
     * 字段：authorizationProperties。
     */
    private final ListingAuthorizationProperties authorizationProperties;

    /**
     * 构造 ListingComparisonReportVerifyRpcServiceImpl 实例。
     */
    @Autowired
    public ListingComparisonReportVerifyRpcServiceImpl(ListingRagIdentity identity,
                                                       ListingComparisonReportStore store,
                                                       ListingAuthorizationProperties authorizationProperties) {
        this.identity = identity;
        this.store = store;
        this.authorizationProperties = authorizationProperties;
    }

    /**
     * 对比报告归属验证：版本化入口未启用时拒绝；验签失败抛出 RagException；
     * 报告缺失或非本人所有统一 NOT_ACCESSIBLE，本人报告返回 ACCESSIBLE 并回带 reportId。
     */
    @Override
    public ListingComparisonReportVerifyResponse verifyReportOwnerContext(
            RagContextAuthorizedComparisonReportVerifyRequest request, RagAuthEnvelope authentication) {
        if (!authorizationProperties.isEnabled()) {
            throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        }
        var principal = identity.verifyAuthorizedComparisonReportVerifyContext(request, authentication,
                authorizationProperties.decodedAuthorityPublicKeys());
        String requestId = request.request().requestId();
        String reportId = request.request().reportId();
        String actorId = Long.toString(principal.userId());
        if (store.findOwnedOrNull(reportId, actorId) == null) {
            return new ListingComparisonReportVerifyResponse(requestId,
                    ListingComparisonReportVerifyResponse.NOT_ACCESSIBLE, null);
        }
        return new ListingComparisonReportVerifyResponse(requestId,
                ListingComparisonReportVerifyResponse.ACCESSIBLE, reportId);
    }
}
