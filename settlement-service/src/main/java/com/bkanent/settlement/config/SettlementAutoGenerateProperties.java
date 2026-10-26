package com.bkanent.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自动生成结算配置。
 */
@ConfigurationProperties(prefix = "settlement.auto-generate")
public class SettlementAutoGenerateProperties {

    /**
     * 业务属性：enabled。
     */
    // No signed, exact service-task permit exists yet; automation stays off until that boundary is added.
    private boolean enabled;
    /**
     * 业务属性：cron。
     */
    private String cron = "0 15 2 * * ?";
    /**
     * 业务属性：monthOffset。
     */
    private int monthOffset = 0;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public int getMonthOffset() {
        return monthOffset;
    }

    public void setMonthOffset(int monthOffset) {
        this.monthOffset = monthOffset;
    }
}
