package com.bkanent.customer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 客户服务提醒配置。
 */
@ConfigurationProperties(prefix = "customer.reminder")
public class CustomerReminderProperties {

    /**
     * 业务属性：enabled。
     */
    private boolean enabled;
    /**
     * 业务属性：entrustExpireDays。
     */
    private int entrustExpireDays = 7;
    /**
     * 业务属性：entrustCron。
     */
    private String entrustCron = "0 0 9 * * ?";

    private String keyId = "customer-service-current";

    private String privateKeyFile = "/run/secrets/service-identity.pem";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getEntrustExpireDays() {
        return entrustExpireDays;
    }

    public void setEntrustExpireDays(int entrustExpireDays) {
        this.entrustExpireDays = entrustExpireDays;
    }

    public String getEntrustCron() {
        return entrustCron;
    }

    public void setEntrustCron(String entrustCron) {
        this.entrustCron = entrustCron;
    }

    public String getKeyId() { return keyId; }

    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getPrivateKeyFile() { return privateKeyFile; }

    public void setPrivateKeyFile(String privateKeyFile) { this.privateKeyFile = privateKeyFile; }
}
