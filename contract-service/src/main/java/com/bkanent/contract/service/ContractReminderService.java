package com.bkanent.contract.service;

/**
 * 合同提醒服务接口。
 */
public interface ContractReminderService {

    /**
     * 业务方法：sendPendingSignReminders。
     */
    int sendPendingSignReminders(int days);
}
