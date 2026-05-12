package com.bkanent.common.rpc;

import com.bkanent.common.model.KpiSummaryDTO;

import java.util.List;

/**
 * BusinessRpcService 服务接口。
 */

public interface BusinessRpcService {

    /**
     * 业务方法：getMonthlyKpis。
     */
    List<KpiSummaryDTO> getMonthlyKpis(String month);
}

