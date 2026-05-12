package com.bkanent.common.rpc;

import com.bkanent.common.model.ContractSettlementDTO;

import java.util.List;

/**
 * 合同 RPC 服务接口。
 */
public interface ContractRpcService {

    /**
     * 业务方法：checkClauseRisk。
     */
    String checkClauseRisk(Long contractId);

    /**
     * 业务方法：listContractsEligibleForSettlement。
     */
    List<ContractSettlementDTO> listContractsEligibleForSettlement(String month);
}
