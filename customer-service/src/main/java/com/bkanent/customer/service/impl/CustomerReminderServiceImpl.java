package com.bkanent.customer.service.impl;

import com.bkanent.common.rpc.NotificationRpcService;
import com.bkanent.customer.config.CustomerReminderProperties;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.entity.OwnerEntrustRecordEntity;
import com.bkanent.customer.service.CustomerDomainService;
import com.bkanent.customer.service.CustomerReminderService;
import com.bkanent.customer.service.OwnerEntrustRecordDomainService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 客户提醒服务实现。
 */
@Service
public class CustomerReminderServiceImpl implements CustomerReminderService {

    private static final Logger log = LoggerFactory.getLogger(CustomerReminderServiceImpl.class);

    private final OwnerEntrustRecordDomainService ownerEntrustRecordDomainService;
    private final CustomerDomainService customerDomainService;
    private final CustomerReminderProperties customerReminderProperties;

    @DubboReference(check = false)
    private NotificationRpcService notificationRpcService;

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
        if (notificationRpcService == null) {
            log.warn("通知服务未就绪，无法发送客户委托到期提醒");
            return 0;
        }

        List<OwnerEntrustRecordEntity> entrustRecords = ownerEntrustRecordDomainService.listExpiringWithinDays(days);
        int successCount = 0;
        for (OwnerEntrustRecordEntity entrustRecord : entrustRecords) {
            CustomerEntity customer = customerDomainService.getById(entrustRecord.getCustomerId());
            if (customer == null || customer.getBrokerId() == null) {
                continue;
            }
            long remainDays = ChronoUnit.DAYS.between(LocalDate.now(), entrustRecord.getEntrustEndDate());
            String title = "业主委托即将到期";
            String content = String.format("业主%s的委托书将在%s天后到期，房源ID=%s，合同编号=%s，请及时跟进续签。",
                    customer.getName(), remainDays, entrustRecord.getListingId(), entrustRecord.getContractNo());
            notificationRpcService.sendStationMessage(customer.getBrokerId(), title, content);
            successCount++;
        }
        log.info("客户委托到期提醒执行完成，days={}，发送数量={}", days, successCount);
        return successCount;
    }

    @Scheduled(cron = "${customer.reminder.entrust-cron:0 0 9 * * ?}")
    public void scheduledEntrustExpiryReminder() {
        sendEntrustExpiryReminders(customerReminderProperties.getEntrustExpireDays());
    }
}
