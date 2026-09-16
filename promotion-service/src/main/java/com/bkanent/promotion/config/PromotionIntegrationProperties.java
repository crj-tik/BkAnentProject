package com.bkanent.promotion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explicit mode for promotion platform integrations.
 */
@ConfigurationProperties(prefix = "promotion.integration")
public class PromotionIntegrationProperties {

    private String mode = "local";

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isLocalMode() {
        return "local".equalsIgnoreCase(mode);
    }
}
