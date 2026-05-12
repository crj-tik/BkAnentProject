package com.bkanent.customer.service;

/**
 * 客户提醒服务接口。
 */
public interface CustomerReminderService {

    /**
     * 业务方法：sendEntrustExpiryReminders。
     */
    int sendEntrustExpiryReminders(int days);
}
