package com.bkanent.contract.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 合同服务配置类。
 */
@Configuration
@EnableConfigurationProperties({ContractReminderProperties.class, ContractIntegrationProperties.class})
public class ContractConfiguration {
}
