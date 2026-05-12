package com.bkanent.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 结算批次配置。
 */
@ConfigurationProperties(prefix = "settlement.batch")
public class SettlementBatchProperties {

    /**
     * 业务属性：batchNoPrefix。
     */
    private String batchNoPrefix = "SETBATCH";

    public String getBatchNoPrefix() {
        return batchNoPrefix;
    }

    public void setBatchNoPrefix(String batchNoPrefix) {
        this.batchNoPrefix = batchNoPrefix;
    }
}
