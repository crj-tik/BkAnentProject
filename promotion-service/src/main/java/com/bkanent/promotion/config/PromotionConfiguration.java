package com.bkanent.promotion.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Promotion integration configuration.
 */
@Configuration
@EnableConfigurationProperties(PromotionIntegrationProperties.class)
public class PromotionConfiguration {

    private final PromotionIntegrationProperties integrationProperties;

    public PromotionConfiguration(PromotionIntegrationProperties integrationProperties) {
        this.integrationProperties = integrationProperties;
    }

    @jakarta.annotation.PostConstruct
    public void validateIntegrationMode() {
        if (integrationProperties.getMode() == null || integrationProperties.getMode().isBlank()
                || "unconfigured".equalsIgnoreCase(integrationProperties.getMode())) {
            throw new IllegalStateException("promotion.integration.mode must be explicitly set to local or real");
        }
    }
}
