package com.bkanent.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GatewayRateLimitProperties 限流配置属性类。
 */
@ConfigurationProperties(prefix = "gateway.rate-limit")
public class GatewayRateLimitProperties {

    /**
     * 业务属性：enabled。
     */
    private boolean enabled = true;
    /**
     * 业务属性：requestsPerWindow。
     */
    private int requestsPerWindow = 100;
    /**
     * 业务属性：windowSeconds。
     */
    private long windowSeconds = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRequestsPerWindow() {
        return requestsPerWindow;
    }

    public void setRequestsPerWindow(int requestsPerWindow) {
        this.requestsPerWindow = requestsPerWindow;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
