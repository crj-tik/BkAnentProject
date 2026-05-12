package com.bkanent.marketing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 营销服务配置类。
 */
@Configuration
@EnableConfigurationProperties(MarketingSearchProperties.class)
public class MarketingConfiguration {
}
