package com.bkanent.business.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务服务 Redis 配置。
 */
@ConfigurationProperties(prefix = "business.redis")
public class BusinessRedisProperties {

    /**
     * 是否启用 Redis 排行榜能力。
     */
    /**
     * 业务属性：enabled。
     */
    private boolean enabled = true;

    /**
     * 排行榜缓存键前缀。
     */
    /**
     * 业务属性：rankingKeyPrefix。
     */
    private String rankingKeyPrefix = "business:ranking:";

    /**
     * 排行榜缓存过期小时数。
     */
    /**
     * 业务属性：rankingTtlHours。
     */
    private long rankingTtlHours = 24L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRankingKeyPrefix() {
        return rankingKeyPrefix;
    }

    public void setRankingKeyPrefix(String rankingKeyPrefix) {
        this.rankingKeyPrefix = rankingKeyPrefix;
    }

    public long getRankingTtlHours() {
        return rankingTtlHours;
    }

    public void setRankingTtlHours(long rankingTtlHours) {
        this.rankingTtlHours = rankingTtlHours;
    }
}
