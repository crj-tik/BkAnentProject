package com.bkanent.settlement.service;

import com.bkanent.settlement.model.SettlementAutoGenerateResponse;
import com.bkanent.settlement.model.SettlementBankCallbackRequest;
import com.bkanent.settlement.model.SettlementCalculateRequest;
import com.bkanent.settlement.model.SettlementDetailResponse;
import com.bkanent.settlement.model.SettlementMonthlySummaryResponse;
import com.bkanent.settlement.model.SettlementPayoutBatchResponse;
import com.bkanent.settlement.model.SettlementPayoutUpdateRequest;
import com.bkanent.settlement.model.SettlementRuleResponse;
import com.bkanent.settlement.model.SettlementRuleUpsertRequest;

import java.util.List;

/**
 * 交易结算管理服务接口。
 */
public interface SettlementManagementService {

    /**
     * 业务方法：saveRule。
     */
    SettlementRuleResponse saveRule(SettlementRuleUpsertRequest request);

    /**
     * 业务方法：listRules。
     */
    List<SettlementRuleResponse> listRules(String contractType);

    /**
     * 业务方法：calculateSettlement。
     */
    SettlementDetailResponse calculateSettlement(SettlementCalculateRequest request);

    /**
     * 业务方法：autoGenerateByContracts。
     */
    SettlementAutoGenerateResponse autoGenerateByContracts(String month);

    /**
     * 业务方法：getSettlementDetail。
     */
    SettlementDetailResponse getSettlementDetail(Long settlementId);

    /**
     * 业务方法：listSettlementByMonth。
     */
    List<SettlementDetailResponse> listSettlementByMonth(String month);

    /**
     * 业务方法：updatePayoutStatus。
     */
    SettlementDetailResponse updatePayoutStatus(Long settlementId, SettlementPayoutUpdateRequest request);

    /**
     * 业务方法：summarizeMonthlyCommission。
     */
    List<SettlementMonthlySummaryResponse> summarizeMonthlyCommission(String month, String summaryScope);

    /**
     * 业务方法：createPayoutBatch。
     */
    SettlementPayoutBatchResponse createPayoutBatch(String month);

    /**
     * 业务方法：markBatchPaid。
     */
    SettlementPayoutBatchResponse markBatchPaid(Long batchId);

    /**
     * 业务方法：handleBankCallback。
     */
    SettlementPayoutBatchResponse handleBankCallback(SettlementBankCallbackRequest request);
}
