package com.bkanent.common.rpc;

import com.bkanent.common.model.CustomerProfileDTO;

import java.util.List;

/**
 * 客户服务 RPC 接口。
 */
public interface CustomerRpcService {

    /**
     * 业务方法：getCustomerProfile。
     */
    CustomerProfileDTO getCustomerProfile(Long customerId);

    /**
     * 业务方法：matchListingsForCustomer。
     */
    List<Long> matchListingsForCustomer(Long customerId);
}
