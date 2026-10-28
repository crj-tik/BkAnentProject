package com.bkanent.businessreview.security;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.LinkedHashMap;
import java.util.Map;
@ConfigurationProperties(prefix = "business-review.authorization")
public class BusinessReviewAuthorizationProperties {
    private boolean enabled = true;
    private String audience = "business-review-service";
    private int maxBodyBytes = 1048576;
    private Map<String, String> authorityPublicKeys = new LinkedHashMap<>();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int value) { maxBodyBytes = value; }
    public Map<String, String> getAuthorityPublicKeys() { return authorityPublicKeys; }
    public void setAuthorityPublicKeys(Map<String, String> keys) {
        authorityPublicKeys = keys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(keys);
    }
}
