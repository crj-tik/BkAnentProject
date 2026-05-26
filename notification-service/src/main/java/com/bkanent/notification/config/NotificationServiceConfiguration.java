package com.bkanent.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotificationWorkflowEventProperties.class)
public class NotificationServiceConfiguration {
}
