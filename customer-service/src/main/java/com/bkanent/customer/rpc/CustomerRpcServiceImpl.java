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
        return customerManagementService.getCustomerDetail(customerId).profile();
    }

    @Override
    public List<Long> matchListingsForCustomer(Long customerId) {
        return customerManagementService.matchListings(customerId).stream()
                .map(match -> match.listing().id())
                .toList();
    }
}
