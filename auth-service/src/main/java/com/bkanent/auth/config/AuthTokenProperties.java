package com.bkanent.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Token settings. The secret must be shared by auth instances in a distributed deployment.
 */
@Component
@ConfigurationProperties(prefix = "auth.token")
public class AuthTokenProperties {

    private String secret;
    private long accessTtlSeconds = 3600;
    private long refreshTtlSeconds = 7 * 24 * 3600;
    private boolean allowRandomSecret;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public void setAccessTtlSeconds(long accessTtlSeconds) {
        this.accessTtlSeconds = accessTtlSeconds;
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    public void setRefreshTtlSeconds(long refreshTtlSeconds) {
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public boolean isAllowRandomSecret() {
        return allowRandomSecret;
    }

    public void setAllowRandomSecret(boolean allowRandomSecret) {
        this.allowRandomSecret = allowRandomSecret;
    }
}
