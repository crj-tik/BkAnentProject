package com.bkanent.settlement.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 交易结算配置类。
 */
@Configuration
@EnableConfigurationProperties({SettlementBatchProperties.class, SettlementAutoGenerateProperties.class})
public class SettlementConfiguration {
}
