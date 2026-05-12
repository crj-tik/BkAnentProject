package com.bkanent.common.rpc;

import java.math.BigDecimal;

/**
 * 交易结算 RPC 服务接口。
 */
public interface SettlementRpcService {

    /**
     * 业务方法：queryMonthlyCommission。
     */
    BigDecimal queryMonthlyCommission(Long employeeId, String month);
}
