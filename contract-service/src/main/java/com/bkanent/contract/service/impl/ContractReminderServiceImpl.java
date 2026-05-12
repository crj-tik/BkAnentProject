package com.bkanent.contract.service.impl;

import com.bkanent.common.rpc.NotificationRpcService;
import com.bkanent.contract.config.ContractReminderProperties;
import com.bkanent.contract.entity.ContractEntity;
import com.bkanent.contract.service.ContractDomainService;
import com.bkanent.contract.service.ContractReminderService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 合同提醒服务实现。
 */
@Service
public class ContractReminderServiceImpl implements ContractReminderService {

    private static final Logger log = LoggerFactory.getLogger(ContractReminderServiceImpl.class);

    private final ContractDomainService contractDomainService;
    private final ContractReminderProperties contractReminderProperties;

    @DubboReference(check = false)
    private NotificationRpcService notificationRpcService;

    public ContractReminderServiceImpl(ContractDomainService contractDomainService,
                                       ContractReminderProperties contractReminderProperties) {
        this.contractDomainService = contractDomainService;
        this.contractReminderProperties = contractReminderProperties;
    }

    @Override
    public int sendPendingSignReminders(int days) {
        if (!contractReminderProperties.isEnabled()) {
            log.info("合同待签提醒未启用，跳过执行");
            return 0;
        }
        if (notificationRpcService == null) {
            log.warn("通知服务未就绪，无法发送合同待签提醒");
            return 0;
        }

        List<ContractEntity> contracts = contractDomainService.listPendingSignContracts(days);
        int successCount = 0;
        for (ContractEntity contract : contracts) {
            if (contract.getBrokerId() == null || !StringUtils.hasText(contract.getExpiryDate())) {
                continue;
            }
            long remainDays = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(contract.getExpiryDate()));
            String title = "合同待签提醒";
            String content = String.format("合同%s将在%s天后到期，客户=%s，房源ID=%s，请尽快推进签署流程。",
                    contract.getContractNo(), remainDays, contract.getCustomerName(), contract.getListingId());
            notificationRpcService.sendStationMessage(contract.getBrokerId(), title, content);
            successCount++;
        }
        log.info("合同待签提醒执行完成，days={}，发送数量={}", days, successCount);
        return successCount;
    }

    @Scheduled(cron = "${contract.reminder.pending-sign-cron:0 30 9 * * ?}")
    public void scheduledPendingSignReminder() {
        sendPendingSignReminders(contractReminderProperties.getPendingSignDays());
    }
}
