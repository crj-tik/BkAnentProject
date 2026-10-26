package com.bkanent.customer.service.impl;

import com.bkanent.customer.config.CustomerReminderProperties;
import com.bkanent.customer.service.CustomerDomainService;
import com.bkanent.customer.service.CustomerReminderService;
import com.bkanent.customer.service.OwnerEntrustRecordDomainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 客户提醒服务实现。
 */
@Service
public class CustomerReminderServiceImpl implements CustomerReminderService {

    private static final Logger log = LoggerFactory.getLogger(CustomerReminderServiceImpl.class);

    private final OwnerEntrustRecordDomainService ownerEntrustRecordDomainService;
    private final CustomerDomainService customerDomainService;
    private final CustomerReminderProperties customerReminderProperties;

    public CustomerReminderServiceImpl(OwnerEntrustRecordDomainService ownerEntrustRecordDomainService,
                                       CustomerDomainService customerDomainService,
                                       CustomerReminderProperties customerReminderProperties) {
        this.ownerEntrustRecordDomainService = ownerEntrustRecordDomainService;
        this.customerDomainService = customerDomainService;
        this.customerReminderProperties = customerReminderProperties;
    }

    @Override
    public int sendEntrustExpiryReminders(int days) {
        if (!customerReminderProperties.isEnabled()) {
            log.info("客户委托到期提醒未启用，跳过执行");
            return 0;
        }
        log.warn("客户委托到期提醒已跳过：brokerId不能作为通知recipientUserId，且旧通知RPC没有签名收件人证明");
        return 0;
    }

    @Scheduled(cron = "${customer.reminder.entrust-cron:0 0 9 * * ?}")
    public void scheduledEntrustExpiryReminder() {
        sendEntrustExpiryReminders(customerReminderProperties.getEntrustExpireDays());
    }
}
