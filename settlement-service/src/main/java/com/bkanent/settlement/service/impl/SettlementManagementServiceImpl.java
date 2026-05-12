package com.bkanent.settlement.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bkanent.common.model.ContractSettlementDTO;
import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.rpc.BusinessRpcService;
import com.bkanent.common.rpc.ContractRpcService;
import com.bkanent.settlement.config.SettlementBatchProperties;
import com.bkanent.settlement.entity.SettlementMonthlySummaryEntity;
import com.bkanent.settlement.entity.SettlementPaymentRecordEntity;
import com.bkanent.settlement.entity.SettlementPayoutBatchEntity;
import com.bkanent.settlement.entity.SettlementRecordEntity;
import com.bkanent.settlement.entity.SettlementRuleEntity;
import com.bkanent.settlement.entity.SettlementRuleTierEntity;
import com.bkanent.settlement.entity.SettlementSplitRecordEntity;
import com.bkanent.settlement.model.SettlementAutoGenerateResponse;
import com.bkanent.settlement.model.SettlementBankCallbackRequest;
import com.bkanent.settlement.model.SettlementBankPaymentItemRequest;
import com.bkanent.settlement.model.SettlementCalculateRequest;
import com.bkanent.settlement.model.SettlementDetailResponse;
import com.bkanent.settlement.model.SettlementMonthlySummaryResponse;
import com.bkanent.settlement.model.SettlementPaymentRecordResponse;
import com.bkanent.settlement.model.SettlementPayoutBatchResponse;
import com.bkanent.settlement.model.SettlementPayoutUpdateRequest;
import com.bkanent.settlement.model.SettlementRuleResponse;
import com.bkanent.settlement.model.SettlementRuleTierRequest;
import com.bkanent.settlement.model.SettlementRuleTierResponse;
import com.bkanent.settlement.model.SettlementRuleUpsertRequest;
import com.bkanent.settlement.model.SettlementSplitResponse;
import com.bkanent.settlement.service.SettlementDomainService;
import com.bkanent.settlement.service.SettlementManagementService;
import com.bkanent.settlement.service.SettlementMonthlySummaryService;
import com.bkanent.settlement.service.SettlementPaymentRecordService;
import com.bkanent.settlement.service.SettlementPayoutBatchService;
import com.bkanent.settlement.service.SettlementRuleService;
import com.bkanent.settlement.service.SettlementRuleTierService;
import com.bkanent.settlement.service.SettlementSplitRecordService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 交易结算管理服务实现。
 */
@Service
public class SettlementManagementServiceImpl implements SettlementManagementService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SettlementDomainService settlementDomainService;
    private final SettlementSplitRecordService settlementSplitRecordService;
    private final SettlementMonthlySummaryService settlementMonthlySummaryService;
    private final SettlementRuleService settlementRuleService;
    private final SettlementRuleTierService settlementRuleTierService;
    private final SettlementPayoutBatchService settlementPayoutBatchService;
    private final SettlementPaymentRecordService settlementPaymentRecordService;
    private final SettlementBatchProperties settlementBatchProperties;

    @DubboReference(check = false)
    private ContractRpcService contractRpcService;

    @DubboReference(check = false)
    private BusinessRpcService businessRpcService;

    public SettlementManagementServiceImpl(SettlementDomainService settlementDomainService,
                                           SettlementSplitRecordService settlementSplitRecordService,
                                           SettlementMonthlySummaryService settlementMonthlySummaryService,
                                           SettlementRuleService settlementRuleService,
                                           SettlementRuleTierService settlementRuleTierService,
                                           SettlementPayoutBatchService settlementPayoutBatchService,
                                           SettlementPaymentRecordService settlementPaymentRecordService,
                                           SettlementBatchProperties settlementBatchProperties) {
        this.settlementDomainService = settlementDomainService;
        this.settlementSplitRecordService = settlementSplitRecordService;
        this.settlementMonthlySummaryService = settlementMonthlySummaryService;
        this.settlementRuleService = settlementRuleService;
        this.settlementRuleTierService = settlementRuleTierService;
        this.settlementPayoutBatchService = settlementPayoutBatchService;
        this.settlementPaymentRecordService = settlementPaymentRecordService;
        this.settlementBatchProperties = settlementBatchProperties;
    }

    @Override
    public SettlementRuleResponse saveRule(SettlementRuleUpsertRequest request) {
        SettlementRuleEntity entity = new SettlementRuleEntity();
        entity.setRuleCode(request.ruleCode());
        entity.setRuleName(request.ruleName());
        entity.setContractType(request.contractType());
        entity.setMinDealAmount(request.minDealAmount());
        entity.setMaxDealAmount(request.maxDealAmount());
        entity.setCommissionRate(request.commissionRate());
        entity.setStoreSplitRatio(request.storeSplitRatio());
        entity.setTeamSplitRatio(request.teamSplitRatio());
        entity.setStatus(defaultIfBlank(request.status(), "ACTIVE"));
        entity.setRemark(request.remark());
        settlementRuleService.save(entity);
        saveRuleTiers(entity.getId(), request.tierRules());
        return toRuleResponse(entity);
    }

    @Override
    public List<SettlementRuleResponse> listRules(String contractType) {
        return settlementRuleService.list().stream()
                .filter(rule -> !StringUtils.hasText(contractType) || contractType.equals(rule.getContractType()))
                .map(this::toRuleResponse)
                .toList();
    }

    @Override
    public SettlementDetailResponse calculateSettlement(SettlementCalculateRequest request) {
        SettlementRuleEntity rule = settlementRuleService.matchRule(request.ruleCode(), request.contractType(), request.dealAmount());
        SettlementRuleTierEntity tierRule = rule == null ? null : settlementRuleTierService.matchTier(rule.getId(), request.dealAmount());
        BigDecimal commissionRate = request.commissionRate() != null ? request.commissionRate()
                : tierRule != null ? tierRule.getCommissionRate()
                : rule == null ? new BigDecimal("0.0100") : rule.getCommissionRate();
        BigDecimal storeSplitRatio = request.storeSplitRatio() != null ? request.storeSplitRatio()
                : tierRule != null ? tierRule.getStoreSplitRatio()
                : rule == null ? null : rule.getStoreSplitRatio();
        BigDecimal teamSplitRatio = request.teamSplitRatio() != null ? request.teamSplitRatio()
                : tierRule != null ? tierRule.getTeamSplitRatio()
                : rule == null ? null : rule.getTeamSplitRatio();

        SettlementRecordEntity entity = buildSettlementRecord(request, commissionRate, rule == null ? null : rule.getRuleCode());
        settlementDomainService.save(entity);
        saveSplitRecords(entity.getId(), request.storeName(), request.teamName(), storeSplitRatio, teamSplitRatio, entity.getCommissionAmount());
        rebuildMonthlySummary(request.statMonth());
        return getSettlementDetail(entity.getId());
    }

    @Override
    public SettlementAutoGenerateResponse autoGenerateByContracts(String month) {
        if (contractRpcService == null) {
            throw new IllegalStateException("合同服务未就绪，无法自动生成结算");
        }
        List<KpiSummaryDTO> kpis = businessRpcService == null ? List.of() : businessRpcService.getMonthlyKpis(month);
        Map<Long, KpiSummaryDTO> kpiMap = kpis.stream().collect(Collectors.toMap(KpiSummaryDTO::employeeId, item -> item, (left, right) -> left));
        List<SettlementDetailResponse> generatedSettlements = new ArrayList<>();
        for (ContractSettlementDTO contract : contractRpcService.listContractsEligibleForSettlement(month)) {
            boolean exists = settlementDomainService.count(new LambdaQueryWrapper<SettlementRecordEntity>()
                    .eq(SettlementRecordEntity::getContractId, contract.contractId())) > 0;
            if (exists) {
                continue;
            }
            KpiSummaryDTO kpi = kpiMap.get(contract.brokerId());
            SettlementRuleEntity rule = settlementRuleService.matchRule(null, contract.contractType(), contract.dealAmount());
            SettlementCalculateRequest request = new SettlementCalculateRequest(
                    contract.brokerId(),
                    kpi == null ? "待补全员工" + contract.brokerId() : kpi.employeeName(),
                    null,
                    null,
                    contract.contractId(),
                    contract.listingId(),
                    contract.contractType(),
                    month,
                    contract.dealAmount(),
                    rule == null ? null : rule.getCommissionRate(),
                    rule == null ? null : rule.getStoreSplitRatio(),
                    rule == null ? null : rule.getTeamSplitRatio(),
                    rule == null ? null : rule.getRuleCode(),
                    "根据已签约合同自动生成结算"
            );
            generatedSettlements.add(calculateSettlement(request));
        }
        return new SettlementAutoGenerateResponse(month, generatedSettlements.size(), generatedSettlements);
    }

    @Override
    public SettlementDetailResponse getSettlementDetail(Long settlementId) {
        SettlementRecordEntity entity = settlementDomainService.getById(settlementId);
        if (entity == null) {
            return null;
        }
        return toDetailResponse(entity, settlementSplitRecordService.listBySettlementId(settlementId));
    }

    @Override
    public List<SettlementDetailResponse> listSettlementByMonth(String month) {
        return settlementDomainService.listByMonth(month).stream()
                .map(entity -> toDetailResponse(entity, settlementSplitRecordService.listBySettlementId(entity.getId())))
                .toList();
    }

    @Override
    public SettlementDetailResponse updatePayoutStatus(Long settlementId, SettlementPayoutUpdateRequest request) {
        SettlementRecordEntity entity = requireSettlement(settlementId);
        entity.setPayoutStatus(request.payoutStatus());
        entity.setRemark(request.remark());
        if ("PAID".equalsIgnoreCase(request.payoutStatus())) {
            entity.setPayoutTime(nowText());
        }
        settlementDomainService.updateById(entity);
        rebuildMonthlySummary(entity.getStatMonth());
        return getSettlementDetail(settlementId);
    }

    @Override
    public List<SettlementMonthlySummaryResponse> summarizeMonthlyCommission(String month, String summaryScope) {
        rebuildMonthlySummary(month);
        return settlementMonthlySummaryService.listByMonth(month, summaryScope).stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    @Override
    public SettlementPayoutBatchResponse createPayoutBatch(String month) {
        List<SettlementRecordEntity> pendingRecords = settlementDomainService.listByMonth(month).stream()
                .filter(record -> "PENDING".equalsIgnoreCase(record.getPayoutStatus()))
                .toList();
        if (pendingRecords.isEmpty()) {
            return null;
        }
        SettlementPayoutBatchEntity batch = new SettlementPayoutBatchEntity();
        batch.setBatchNo(buildBatchNo(month));
        batch.setStatMonth(month);
        batch.setBatchStatus("PROCESSING");
        batch.setTotalRecords(pendingRecords.size());
        batch.setTotalAmount(pendingRecords.stream().map(SettlementRecordEntity::getCommissionAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        batch.setSubmitTime(nowText());
        batch.setRemark("月度发放批次");
        settlementPayoutBatchService.save(batch);

        List<SettlementPaymentRecordEntity> payments = new ArrayList<>();
        for (SettlementRecordEntity record : pendingRecords) {
            record.setPayoutStatus("PROCESSING");
            settlementDomainService.updateById(record);
            SettlementPaymentRecordEntity payment = new SettlementPaymentRecordEntity();
            payment.setBatchId(batch.getId());
            payment.setSettlementId(record.getId());
            payment.setPayeeEmployeeId(record.getEmployeeId());
            payment.setPayeeName(record.getEmployeeName());
            payment.setPaymentAmount(record.getCommissionAmount());
            payment.setPaymentStatus("PROCESSING");
            payment.setRemark("批次发放处理中");
            payments.add(payment);
        }
        settlementPaymentRecordService.saveBatch(payments);
        rebuildMonthlySummary(month);
        return getBatchResponse(batch.getId());
    }

    @Override
    public SettlementPayoutBatchResponse markBatchPaid(Long batchId) {
        SettlementPayoutBatchEntity batch = requireBatch(batchId);
        batch.setBatchStatus("PAID");
        batch.setPaidTime(nowText());
        settlementPayoutBatchService.updateById(batch);
        List<SettlementPaymentRecordEntity> payments = settlementPaymentRecordService.listByBatchId(batchId);
        for (SettlementPaymentRecordEntity payment : payments) {
            payment.setPaymentStatus("PAID");
            payment.setPaymentTime(nowText());
            payment.setBankSerialNo("BANK-" + payment.getId());
            settlementPaymentRecordService.updateById(payment);
            SettlementRecordEntity record = requireSettlement(payment.getSettlementId());
            record.setPayoutStatus("PAID");
            record.setPayoutTime(payment.getPaymentTime());
            settlementDomainService.updateById(record);
        }
        rebuildMonthlySummary(batch.getStatMonth());
        return getBatchResponse(batchId);
    }

    @Override
    public SettlementPayoutBatchResponse handleBankCallback(SettlementBankCallbackRequest request) {
        SettlementPayoutBatchEntity batch = settlementPayoutBatchService.getOne(new LambdaQueryWrapper<SettlementPayoutBatchEntity>()
                .eq(SettlementPayoutBatchEntity::getBatchNo, request.batchNo())
                .last("limit 1"));
        if (batch == null) {
            throw new IllegalArgumentException("发放批次不存在: " + request.batchNo());
        }
        batch.setBatchStatus(defaultIfBlank(request.callbackStatus(), "PROCESSING"));
        batch.setRemark(request.remark());
        if ("PAID".equalsIgnoreCase(request.callbackStatus())) {
            batch.setPaidTime(defaultIfBlank(request.callbackTime(), nowText()));
        }
        settlementPayoutBatchService.updateById(batch);

        if (request.paymentItems() != null) {
            for (SettlementBankPaymentItemRequest item : request.paymentItems()) {
                SettlementPaymentRecordEntity payment = settlementPaymentRecordService.getOne(new LambdaQueryWrapper<SettlementPaymentRecordEntity>()
                        .eq(SettlementPaymentRecordEntity::getBatchId, batch.getId())
                        .eq(SettlementPaymentRecordEntity::getSettlementId, item.settlementId())
                        .last("limit 1"));
                if (payment == null) {
                    continue;
                }
                payment.setPaymentStatus(item.paymentStatus());
                payment.setBankSerialNo(item.bankSerialNo());
                payment.setPaymentTime(defaultIfBlank(item.paymentTime(), nowText()));
                payment.setRemark(item.remark());
                settlementPaymentRecordService.updateById(payment);

                SettlementRecordEntity settlement = requireSettlement(item.settlementId());
                settlement.setPayoutStatus(item.paymentStatus());
                settlement.setPayoutTime(payment.getPaymentTime());
                settlementDomainService.updateById(settlement);
            }
        }
        rebuildMonthlySummary(batch.getStatMonth());
        return getBatchResponse(batch.getId());
    }

    private SettlementRecordEntity buildSettlementRecord(SettlementCalculateRequest request,
                                                         BigDecimal commissionRate,
                                                         String matchedRuleCode) {
        BigDecimal dealAmount = defaultDecimal(request.dealAmount(), BigDecimal.ZERO);
        BigDecimal actualRate = defaultDecimal(commissionRate, new BigDecimal("0.0100"));
        BigDecimal commissionAmount = dealAmount.multiply(actualRate).setScale(2, RoundingMode.HALF_UP);
        SettlementRecordEntity entity = new SettlementRecordEntity();
        entity.setEmployeeId(request.employeeId());
        entity.setEmployeeName(request.employeeName());
        entity.setTeamName(request.teamName());
        entity.setStoreName(request.storeName());
        entity.setContractId(request.contractId());
        entity.setListingId(request.listingId());
        entity.setStatMonth(request.statMonth());
        entity.setDealAmount(dealAmount);
        entity.setCommissionRate(actualRate);
        entity.setCommissionAmount(commissionAmount);
        entity.setPayoutStatus("PENDING");
        entity.setRuleCode(defaultIfBlank(request.ruleCode(), defaultIfBlank(matchedRuleCode, "DEFAULT_COMMISSION_RULE")));
        entity.setRemark(request.remark());
        return entity;
    }

    private void saveSplitRecords(Long settlementId,
                                  String storeName,
                                  String teamName,
                                  BigDecimal storeSplitRatio,
                                  BigDecimal teamSplitRatio,
                                  BigDecimal commissionAmount) {
        List<SettlementSplitRecordEntity> splitRecords = new ArrayList<>();
        addSplitRecord(splitRecords, settlementId, "STORE", storeName, storeSplitRatio, commissionAmount, "跨店分佣");
        addSplitRecord(splitRecords, settlementId, "TEAM", teamName, teamSplitRatio, commissionAmount, "跨组分佣");
        if (!splitRecords.isEmpty()) {
            settlementSplitRecordService.saveBatch(splitRecords);
        }
    }

    private void addSplitRecord(List<SettlementSplitRecordEntity> splitRecords,
                                Long settlementId,
                                String splitScope,
                                String targetName,
                                BigDecimal splitRatio,
                                BigDecimal commissionAmount,
                                String remark) {
        if (!StringUtils.hasText(targetName) || splitRatio == null || splitRatio.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        SettlementSplitRecordEntity splitRecord = new SettlementSplitRecordEntity();
        splitRecord.setSettlementId(settlementId);
        splitRecord.setSplitScope(splitScope);
        splitRecord.setSplitTargetName(targetName);
        splitRecord.setSplitRatio(splitRatio);
        splitRecord.setSplitAmount(commissionAmount.multiply(splitRatio).setScale(2, RoundingMode.HALF_UP));
        splitRecord.setRemark(remark);
        splitRecords.add(splitRecord);
    }

    private void saveRuleTiers(Long ruleId, List<SettlementRuleTierRequest> tierRequests) {
        if (tierRequests == null || tierRequests.isEmpty()) {
            return;
        }
        List<SettlementRuleTierEntity> tierEntities = tierRequests.stream()
                .filter(Objects::nonNull)
                .map(item -> {
                    SettlementRuleTierEntity entity = new SettlementRuleTierEntity();
                    entity.setRuleId(ruleId);
                    entity.setTierLevel(item.tierLevel());
                    entity.setMinDealAmount(item.minDealAmount());
                    entity.setMaxDealAmount(item.maxDealAmount());
                    entity.setCommissionRate(item.commissionRate());
                    entity.setStoreSplitRatio(item.storeSplitRatio());
                    entity.setTeamSplitRatio(item.teamSplitRatio());
                    entity.setRemark(item.remark());
                    return entity;
                }).toList();
        settlementRuleTierService.saveBatch(tierEntities);
    }

    private void rebuildMonthlySummary(String month) {
        List<SettlementRecordEntity> records = settlementDomainService.listByMonth(month);
        settlementMonthlySummaryService.remove(new LambdaQueryWrapper<SettlementMonthlySummaryEntity>()
                .eq(SettlementMonthlySummaryEntity::getStatMonth, month));
        if (records.isEmpty()) {
            return;
        }

        Map<Long, List<SettlementRecordEntity>> personalMap = records.stream()
                .collect(Collectors.groupingBy(SettlementRecordEntity::getEmployeeId));
        List<SettlementMonthlySummaryEntity> summaries = new ArrayList<>();
        personalMap.forEach((employeeId, employeeRecords) -> summaries.add(buildPersonalSummary(month, employeeId, employeeRecords)));

        Map<String, List<SettlementRecordEntity>> teamMap = records.stream()
                .filter(record -> StringUtils.hasText(record.getTeamName()))
                .collect(Collectors.groupingBy(SettlementRecordEntity::getTeamName));
        teamMap.forEach((teamName, teamRecords) -> summaries.add(buildTeamSummary(month, teamName, teamRecords)));

        settlementMonthlySummaryService.saveBatch(summaries);
    }

    private SettlementMonthlySummaryEntity buildPersonalSummary(String month,
                                                                Long employeeId,
                                                                List<SettlementRecordEntity> employeeRecords) {
        SettlementRecordEntity firstRecord = employeeRecords.get(0);
        SettlementMonthlySummaryEntity entity = new SettlementMonthlySummaryEntity();
        entity.setSummaryScope("PERSONAL");
        entity.setEmployeeId(employeeId);
        entity.setEmployeeName(firstRecord.getEmployeeName());
        entity.setTeamName(firstRecord.getTeamName());
        entity.setStatMonth(month);
        entity.setDealCount(employeeRecords.size());
        entity.setTotalDealAmount(sumDealAmount(employeeRecords));
        entity.setTotalCommissionAmount(sumCommissionAmount(employeeRecords));
        entity.setPayoutStatus(resolvePayoutStatus(employeeRecords));
        entity.setRemark("个人月度提成汇总");
        return entity;
    }

    private SettlementMonthlySummaryEntity buildTeamSummary(String month,
                                                            String teamName,
                                                            List<SettlementRecordEntity> teamRecords) {
        SettlementMonthlySummaryEntity entity = new SettlementMonthlySummaryEntity();
        entity.setSummaryScope("TEAM");
        entity.setEmployeeName(teamName);
        entity.setTeamName(teamName);
        entity.setStatMonth(month);
        entity.setDealCount(teamRecords.size());
        entity.setTotalDealAmount(sumDealAmount(teamRecords));
        entity.setTotalCommissionAmount(sumCommissionAmount(teamRecords));
        entity.setPayoutStatus(resolvePayoutStatus(teamRecords));
        entity.setRemark("团队月度提成汇总");
        return entity;
    }

    private BigDecimal sumDealAmount(List<SettlementRecordEntity> records) {
        return records.stream().map(SettlementRecordEntity::getDealAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumCommissionAmount(List<SettlementRecordEntity> records) {
        return records.stream().map(SettlementRecordEntity::getCommissionAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String resolvePayoutStatus(List<SettlementRecordEntity> records) {
        boolean allPaid = records.stream().allMatch(record -> "PAID".equalsIgnoreCase(record.getPayoutStatus()));
        boolean anyProcessing = records.stream().anyMatch(record -> "PROCESSING".equalsIgnoreCase(record.getPayoutStatus()));
        if (allPaid) {
            return "PAID";
        }
        return anyProcessing ? "PROCESSING" : "PENDING";
    }

    private SettlementDetailResponse toDetailResponse(SettlementRecordEntity entity,
                                                      List<SettlementSplitRecordEntity> splitRecords) {
        return new SettlementDetailResponse(
                entity.getId(),
                entity.getEmployeeId(),
                entity.getEmployeeName(),
                entity.getTeamName(),
                entity.getStoreName(),
                entity.getContractId(),
                entity.getListingId(),
                entity.getStatMonth(),
                entity.getDealAmount(),
                entity.getCommissionRate(),
                entity.getCommissionAmount(),
                entity.getPayoutStatus(),
                entity.getPayoutTime(),
                entity.getRuleCode(),
                entity.getRemark(),
                splitRecords.stream().map(this::toSplitResponse).toList()
        );
    }

    private SettlementSplitResponse toSplitResponse(SettlementSplitRecordEntity entity) {
        return new SettlementSplitResponse(
                entity.getId(),
                entity.getSettlementId(),
                entity.getSplitScope(),
                entity.getSplitTargetName(),
                entity.getSplitRatio(),
                entity.getSplitAmount(),
                entity.getRemark()
        );
    }

    private SettlementMonthlySummaryResponse toSummaryResponse(SettlementMonthlySummaryEntity entity) {
        return new SettlementMonthlySummaryResponse(
                entity.getId(),
                entity.getSummaryScope(),
                entity.getEmployeeId(),
                entity.getEmployeeName(),
                entity.getTeamName(),
                entity.getStatMonth(),
                entity.getDealCount(),
                entity.getTotalDealAmount(),
                entity.getTotalCommissionAmount(),
                entity.getPayoutStatus(),
                entity.getRemark()
        );
    }

    private SettlementRuleResponse toRuleResponse(SettlementRuleEntity entity) {
        List<SettlementRuleTierResponse> tierResponses = settlementRuleTierService.listByRuleId(entity.getId()).stream()
                .map(this::toTierResponse)
                .toList();
        return new SettlementRuleResponse(
                entity.getId(),
                entity.getRuleCode(),
                entity.getRuleName(),
                entity.getContractType(),
                entity.getMinDealAmount(),
                entity.getMaxDealAmount(),
                entity.getCommissionRate(),
                entity.getStoreSplitRatio(),
                entity.getTeamSplitRatio(),
                entity.getStatus(),
                entity.getRemark(),
                tierResponses
        );
    }

    private SettlementRuleTierResponse toTierResponse(SettlementRuleTierEntity entity) {
        return new SettlementRuleTierResponse(
                entity.getId(),
                entity.getRuleId(),
                entity.getTierLevel(),
                entity.getMinDealAmount(),
                entity.getMaxDealAmount(),
                entity.getCommissionRate(),
                entity.getStoreSplitRatio(),
                entity.getTeamSplitRatio(),
                entity.getRemark()
        );
    }

    private SettlementPayoutBatchResponse getBatchResponse(Long batchId) {
        SettlementPayoutBatchEntity batch = settlementPayoutBatchService.getById(batchId);
        if (batch == null) {
            return null;
        }
        return new SettlementPayoutBatchResponse(
                batch.getId(),
                batch.getBatchNo(),
                batch.getStatMonth(),
                batch.getBatchStatus(),
                batch.getTotalRecords(),
                batch.getTotalAmount(),
                batch.getSubmitTime(),
                batch.getPaidTime(),
                batch.getRemark(),
                settlementPaymentRecordService.listByBatchId(batchId).stream().map(this::toPaymentResponse).toList()
        );
    }

    private SettlementPaymentRecordResponse toPaymentResponse(SettlementPaymentRecordEntity entity) {
        return new SettlementPaymentRecordResponse(
                entity.getId(),
                entity.getBatchId(),
                entity.getSettlementId(),
                entity.getPayeeEmployeeId(),
                entity.getPayeeName(),
                entity.getPaymentAmount(),
                entity.getPaymentStatus(),
                entity.getPaymentTime(),
                entity.getBankSerialNo(),
                entity.getRemark()
        );
    }

    private SettlementRecordEntity requireSettlement(Long settlementId) {
        SettlementRecordEntity entity = settlementDomainService.getById(settlementId);
        if (entity == null) {
            throw new IllegalArgumentException("结算记录不存在: " + settlementId);
        }
        return entity;
    }

    private SettlementPayoutBatchEntity requireBatch(Long batchId) {
        SettlementPayoutBatchEntity batch = settlementPayoutBatchService.getById(batchId);
        if (batch == null) {
            throw new IllegalArgumentException("发放批次不存在: " + batchId);
        }
        return batch;
    }

    private String buildBatchNo(String month) {
        return settlementBatchProperties.getBatchNoPrefix() + "-" + month.replace("-", "") + "-" + System.currentTimeMillis();
    }

    private String nowText() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }

    private BigDecimal defaultDecimal(BigDecimal value, BigDecimal defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
