package com.bkanent.business.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 平台经营分析配置。
 */
@Configuration
@EnableConfigurationProperties({AnalyticsRankingProperties.class, AnalyticsRedisProperties.class})
public class AnalyticsConfiguration {
}
