package com.bkanent.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explicit mode for media generation integrations.
 */
@ConfigurationProperties(prefix = "media.integration")
public class MediaIntegrationProperties {

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
