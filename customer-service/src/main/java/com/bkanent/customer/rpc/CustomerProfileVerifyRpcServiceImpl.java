package com.bkanent.customer.rpc;

import com.bkanent.common.rpc.CustomerProfileVerifyResponse;
import com.bkanent.common.rpc.CustomerProfileVerifyRpcService;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedCustomerProfileVerifyRequest;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.service.CustomerDomainService;
import org.apache.dubbo.config.annotation.DubboService;

import java.time.ZoneId;

/**
 * Listing→Customer 卖方档案验证 RPC 提供方。
 *
 * <p>NOT_FOUND / NOT_ACCESSIBLE / INVALID_PROFILE_TYPE 是正常返回值（不抛异常），
 * 仅验签或上下文失败才抛出 RagException；档案事实只来自本地真源，
 * 不以请求字段推断归属。业务管理员（customer.read.all）放行任意档案，
 * 非管理员要求档案 brokerId 与当前 BROKER 映射一致。
 */
@DubboService(serialization = "fastjson2ctor", preferSerialization = "fastjson2ctor")
public class CustomerProfileVerifyRpcServiceImpl implements CustomerProfileVerifyRpcService {

    private final CustomerRpcIdentity identity;
    private final CustomerDomainService customerDomainService;

    public CustomerProfileVerifyRpcServiceImpl(CustomerRpcIdentity identity,
                                               CustomerDomainService customerDomainService) {
        this.identity = identity;
        this.customerDomainService = customerDomainService;
    }

    @Override
    public CustomerProfileVerifyResponse verifyOwnerProfileContext(
            RagContextAuthorizedCustomerProfileVerifyRequest request, RagAuthEnvelope authentication) {
        CustomerRpcIdentity.VerifiedProfileCaller caller = identity.verifyOwnerProfileCaller(request, authentication);
        CustomerEntity profile = customerDomainService.getById(request.request().customerId());
        if (profile == null) {
            return notVerified(request, CustomerProfileVerifyResponse.NOT_FOUND);
        }
        if (profile.getProfileType() == null
                || !request.request().expectedProfileType().equalsIgnoreCase(profile.getProfileType())) {
            return notVerified(request, CustomerProfileVerifyResponse.INVALID_PROFILE_TYPE);
        }
        boolean ownerMatches = profile.getOwnerUserId() != null
                ? profile.getOwnerUserId().equals(caller.actorUserId())
                : caller.brokerId() != null && caller.brokerId().equals(profile.getBrokerId());
        if (!caller.businessWide() && !ownerMatches) {
            return notVerified(request, CustomerProfileVerifyResponse.NOT_ACCESSIBLE);
        }
        return new CustomerProfileVerifyResponse(request.request().requestId(),
                CustomerProfileVerifyResponse.VERIFIED, profile.getId(), profile.getProfileType(),
                profile.getBrokerId(), epochSecond(profile.getUpdatedAt()));
    }

    private static CustomerProfileVerifyResponse notVerified(
            RagContextAuthorizedCustomerProfileVerifyRequest request, String result) {
        return new CustomerProfileVerifyResponse(request.request().requestId(), result,
                null, null, null, null);
    }

    private static Long epochSecond(java.time.LocalDateTime updatedAt) {
        return updatedAt == null ? null : updatedAt.atZone(ZoneId.systemDefault()).toEpochSecond();
    }
}
