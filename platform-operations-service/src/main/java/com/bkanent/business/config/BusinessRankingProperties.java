package com.bkanent.business.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务排行榜配置。
 */
@ConfigurationProperties(prefix = "business.ranking")
public class BusinessRankingProperties {

    /**
     * 业务属性：useRedis。
     */
    private boolean useRedis = true;
    /**
     * 业务属性：keyPrefix。
     */
    private String keyPrefix = "business:ranking:";

    public boolean isUseRedis() {
        return useRedis;
    }

    public void setUseRedis(boolean useRedis) {
        this.useRedis = useRedis;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
