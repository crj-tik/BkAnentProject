package com.bkanent.business.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "business.authorization")
public class BusinessAuthorizationProperties {
    private boolean enabled;
    private String audience = "platform-operations-service";
    private int maxBodyBytes = 1024 * 1024;
    private Map<String, String> authorityPublicKeys = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
    public Map<String, String> getAuthorityPublicKeys() { return authorityPublicKeys; }
    public void setAuthorityPublicKeys(Map<String, String> authorityPublicKeys) {
        this.authorityPublicKeys = authorityPublicKeys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(authorityPublicKeys);
    }
}
