package com.bkanent.compare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 房源对比报告存储配置。
 */
@Component
@ConfigurationProperties(prefix = "compare.report")
public class CompareReportProperties {

    /**
     * 报告文件存储目录。
     */
    /**
     * 业务属性：storageDir。
     */
    private String storageDir = "compare-engine-service/runtime/reports";

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }
}
