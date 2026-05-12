package com.bkanent.customer.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 客户服务配置类。
 */
@Configuration
@EnableConfigurationProperties(CustomerReminderProperties.class)
public class CustomerConfiguration {
}
