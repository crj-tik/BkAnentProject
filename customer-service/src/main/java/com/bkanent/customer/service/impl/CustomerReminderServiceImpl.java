package com.bkanent.customer.service.impl;

import com.bkanent.customer.config.CustomerReminderProperties;
import com.bkanent.customer.service.CustomerDomainService;
import com.bkanent.customer.service.CustomerNotificationPermitClient;
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
    private final CustomerNotificationPermitClient notificationPermitClient;

    public CustomerReminderServiceImpl(OwnerEntrustRecordDomainService ownerEntrustRecordDomainService,
                                       CustomerDomainService customerDomainService,
                                       CustomerReminderProperties customerReminderProperties,
                                       CustomerNotificationPermitClient notificationPermitClient) {
        this.ownerEntrustRecordDomainService = ownerEntrustRecordDomainService;
        this.customerDomainService = customerDomainService;
        this.customerReminderProperties = customerReminderProperties;
        this.notificationPermitClient = notificationPermitClient;
    }

    @Override
    public int sendEntrustExpiryReminders(int days) {
        if (!customerReminderProperties.isEnabled()) {
            log.info("客户委托到期提醒未启用，跳过执行");
            return 0;
        }
        if (days < 1 || days > 365) throw new IllegalArgumentException("提醒天数必须在 1 到 365 天之间");
        int sent = 0;
        for (var entrust : ownerEntrustRecordDomainService.listExpiringWithinDays(days)) {
            if (entrust.getId() == null || entrust.getCustomerId() == null) continue;
            var ownerProfile = customerDomainService.getById(entrust.getCustomerId());
            if (ownerProfile == null || ownerProfile.getOwnerUserId() == null || ownerProfile.getOwnerUserId() <= 0) {
                log.warn("客户委托提醒因缺少已迁移的用户负责人而跳过，entrustId={}", entrust.getId());
                continue;
            }
            String title = "房屋委托即将到期";
            String expiry = entrust.getEntrustEndDate() == null ? "近期" : entrust.getEntrustEndDate().toString();
            String content = "您负责的业主委托记录即将到期，到期日期：" + expiry + "。请核实并及时跟进。";
            String idempotencyKey = "customer-entrust-expiring:" + entrust.getId() + ":" + expiry;
            try {
                notificationPermitClient.sendEntrustReminder(ownerProfile.getOwnerUserId(), title, content,
                        idempotencyKey);
                sent++;
            } catch (RuntimeException failure) {
                log.warn("客户委托提醒发送失败，保持可重试，entrustId={}", entrust.getId());
            }
        }
        return sent;
    }

    @Scheduled(cron = "${customer.reminder.entrust-cron:0 0 9 * * ?}")
    public void scheduledEntrustExpiryReminder() {
        sendEntrustExpiryReminders(customerReminderProperties.getEntrustExpireDays());
    }
}
