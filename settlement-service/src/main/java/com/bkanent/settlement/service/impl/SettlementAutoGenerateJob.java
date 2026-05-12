package com.bkanent.settlement.service.impl;

import com.bkanent.settlement.config.SettlementAutoGenerateProperties;
import com.bkanent.settlement.service.SettlementManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 自动生成结算定时任务。
 */
@Component
public class SettlementAutoGenerateJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementAutoGenerateJob.class);
    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final SettlementManagementService settlementManagementService;
    private final SettlementAutoGenerateProperties settlementAutoGenerateProperties;

    public SettlementAutoGenerateJob(SettlementManagementService settlementManagementService,
                                     SettlementAutoGenerateProperties settlementAutoGenerateProperties) {
        this.settlementManagementService = settlementManagementService;
        this.settlementAutoGenerateProperties = settlementAutoGenerateProperties;
    }

    @Scheduled(cron = "${settlement.auto-generate.cron:0 15 2 * * ?}")
    public void autoGenerateSettlement() {
        if (!settlementAutoGenerateProperties.isEnabled()) {
            log.info("自动生成结算任务未启用，跳过执行");
            return;
        }
        String month = LocalDate.now().plusMonths(settlementAutoGenerateProperties.getMonthOffset()).format(MONTH_FORMATTER);
        int generatedCount = settlementManagementService.autoGenerateByContracts(month).generatedCount();
        log.info("自动生成结算任务执行完成，月份={}，生成数量={}", month, generatedCount);
    }
}
