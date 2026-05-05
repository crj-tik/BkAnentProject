package com.bkanent.common.rpc;

import java.math.BigDecimal;

public interface SettlementRpcService {

    BigDecimal queryMonthlyCommission(Long employeeId, String month);
}
