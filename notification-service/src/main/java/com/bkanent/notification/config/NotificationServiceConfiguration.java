package com.bkanent.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({NotificationWorkflowEventProperties.class, NotificationIntegrationProperties.class})
public class NotificationServiceConfiguration {

    private final NotificationIntegrationProperties integrationProperties;

    public NotificationServiceConfiguration(NotificationIntegrationProperties integrationProperties) {
        this.integrationProperties = integrationProperties;
    }

    @jakarta.annotation.PostConstruct
    public void validateIntegrationMode() {
        if (integrationProperties.getMode() == null || integrationProperties.getMode().isBlank()
                || "unconfigured".equalsIgnoreCase(integrationProperties.getMode())) {
            throw new IllegalStateException("notification.integration.mode must be explicitly set to local or real");
        }
    }
}
