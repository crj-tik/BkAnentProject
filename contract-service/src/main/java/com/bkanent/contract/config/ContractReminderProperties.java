package com.bkanent.contract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 合同提醒配置。
 */
@ConfigurationProperties(prefix = "contract.reminder")
public class ContractReminderProperties {

    /**
     * 业务属性：enabled。
     */
    private boolean enabled = true;
    /**
     * 业务属性：pendingSignDays。
     */
    private int pendingSignDays = 3;
    /**
     * 业务属性：pendingSignCron。
     */
    private String pendingSignCron = "0 30 9 * * ?";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPendingSignDays() {
        return pendingSignDays;
    }

    public void setPendingSignDays(int pendingSignDays) {
        this.pendingSignDays = pendingSignDays;
    }

    public String getPendingSignCron() {
        return pendingSignCron;
    }

    public void setPendingSignCron(String pendingSignCron) {
        this.pendingSignCron = pendingSignCron;
    }
}
