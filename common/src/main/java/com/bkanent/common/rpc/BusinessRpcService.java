package com.bkanent.common.rpc;

import com.bkanent.common.model.KpiSummaryDTO;

import java.util.List;

public interface BusinessRpcService {

    List<KpiSummaryDTO> getMonthlyKpis(String month);
}
