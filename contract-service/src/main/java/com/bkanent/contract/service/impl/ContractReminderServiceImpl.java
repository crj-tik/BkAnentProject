package com.bkanent.contract.service.impl;

import com.bkanent.contract.config.ContractReminderProperties;
import com.bkanent.contract.service.ContractDomainService;
import com.bkanent.contract.service.ContractReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 合同提醒服务实现。
 */
@Service
public class ContractReminderServiceImpl implements ContractReminderService {

    private static final Logger log = LoggerFactory.getLogger(ContractReminderServiceImpl.class);

    private final ContractDomainService contractDomainService;
    private final ContractReminderProperties contractReminderProperties;

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
        log.warn("合同待签提醒已跳过：brokerId不能作为通知recipientUserId，且旧通知RPC没有签名收件人证明");
        return 0;
    }

    @Scheduled(cron = "${contract.reminder.pending-sign-cron:0 30 9 * * ?}")
    public void scheduledPendingSignReminder() {
        sendPendingSignReminders(contractReminderProperties.getPendingSignDays());
    }
}
