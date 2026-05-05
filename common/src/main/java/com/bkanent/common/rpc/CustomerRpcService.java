package com.bkanent.common.rpc;

import java.util.List;

public interface CustomerRpcService {

    List<Long> matchListingsForCustomer(Long customerId);
}
