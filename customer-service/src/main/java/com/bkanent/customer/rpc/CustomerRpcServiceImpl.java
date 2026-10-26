package com.bkanent.customer.rpc;

import com.bkanent.common.model.CustomerProfileDTO;
import com.bkanent.common.rpc.CustomerRpcService;
import com.bkanent.customer.service.CustomerManagementService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * 客户服务 RPC 实现。
 */
@DubboService
public class CustomerRpcServiceImpl implements CustomerRpcService {

    private final CustomerManagementService customerManagementService;

    public CustomerRpcServiceImpl(CustomerManagementService customerManagementService) {
        this.customerManagementService = customerManagementService;
    }

    @Override
    public CustomerProfileDTO getCustomerProfile(Long customerId) {
        throw new com.bkanent.common.authz.AuthzException(
                com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Override
    public List<Long> matchListingsForCustomer(Long customerId) {
        throw new com.bkanent.common.authz.AuthzException(
                com.bkanent.common.authz.AuthzErrorCode.AUTHENTICATION_REQUIRED);
    }
}
