package com.bkanent.customer.service;

import com.bkanent.common.rpc.CustomerRpcService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
public class CustomerRpcServiceImpl implements CustomerRpcService {

    private final CustomerDomainService customerDomainService;

    public CustomerRpcServiceImpl(CustomerDomainService customerDomainService) {
        this.customerDomainService = customerDomainService;
    }

    @Override
    public List<Long> matchListingsForCustomer(Long customerId) {
        return customerDomainService.getById(customerId) == null ? List.of() : List.of(1L, 2L, 3L);
    }
}
