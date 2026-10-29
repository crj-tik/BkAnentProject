package com.bkanent.business.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 业务服务配置类。
 */
@Configuration
@EnableConfigurationProperties({BusinessRankingProperties.class, BusinessRedisProperties.class})
public class BusinessConfiguration {
}
