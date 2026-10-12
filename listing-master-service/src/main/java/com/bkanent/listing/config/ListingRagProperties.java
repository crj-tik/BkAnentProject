package com.bkanent.listing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Managed policy, independent domain signing keys, and no backend configuration. */
@Component
@ConfigurationProperties(prefix = "listing.rag")
public class ListingRagProperties {
    private String keyId = "listing-current";
    private String secret;
    private String authResponseKeyId = "auth-response-current";
    private String authResponseSecret;
    private String policyVersion = "listing-v1";
    private String schemaVersion = "1";
    private Set<String> readRoleCodes = Set.of();
    private Set<String> adminRoleCodes = Set.of();
    private boolean dispatcherEnabled;
    private boolean legacyReconciliationEnabled;
    private Map<String, TrustedCaller> trustedCallers = new LinkedHashMap<>();
    public String getKeyId() { return keyId; }
    public void setKeyId(String value) { keyId = value; }
    public String getSecret() { return secret; }
    public void setSecret(String value) { secret = value; }
    public String getAuthResponseKeyId() { return authResponseKeyId; }
    public void setAuthResponseKeyId(String value) { authResponseKeyId = value; }
    public String getAuthResponseSecret() { return authResponseSecret; }
    public void setAuthResponseSecret(String value) { authResponseSecret = value; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String value) { policyVersion = value; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String value) { schemaVersion = value; }
    public Set<String> getReadRoleCodes() { return readRoleCodes; }
    public void setReadRoleCodes(Set<String> value) { readRoleCodes = value == null ? Set.of() : Set.copyOf(value); }
    public Set<String> getAdminRoleCodes() { return adminRoleCodes; }
    public void setAdminRoleCodes(Set<String> value) { adminRoleCodes = value == null ? Set.of() : Set.copyOf(value); }
    public boolean isDispatcherEnabled() { return dispatcherEnabled; }
    public void setDispatcherEnabled(boolean value) { dispatcherEnabled = value; }
    public boolean isLegacyReconciliationEnabled() { return legacyReconciliationEnabled; }
    public void setLegacyReconciliationEnabled(boolean value) { legacyReconciliationEnabled = value; }
    public Map<String, TrustedCaller> getTrustedCallers() { return trustedCallers; }
    public void setTrustedCallers(Map<String, TrustedCaller> value) { trustedCallers = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
    public static class TrustedCaller {
        private String callerService;
        private String secret;
        public String getCallerService() { return callerService; }
        public void setCallerService(String value) { callerService = value; }
        public String getSecret() { return secret; }
        public void setSecret(String value) { secret = value; }
    }
}
