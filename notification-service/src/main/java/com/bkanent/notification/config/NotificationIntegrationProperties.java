package com.bkanent.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explicit mode for external notification integrations.
 */
@ConfigurationProperties(prefix = "notification.integration")
public class NotificationIntegrationProperties {

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
