package com.bkanent.common.authz;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared option shape for domain-local Gateway ingress verification. */
public class AuthzIngressProperties {
    private boolean enabled;
    private int maxBodyBytes = AuthzContract.MAX_BODY_BYTES;
    private String audience;
    private Map<String, String> authorityPublicKeys = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int value) { maxBodyBytes = value; }
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public Map<String, String> getAuthorityPublicKeys() { return authorityPublicKeys; }
    public void setAuthorityPublicKeys(Map<String, String> values) {
        authorityPublicKeys = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
    }

    public void validate(String expectedAudience) {
        if (!enabled) return;
        if (!expectedAudience.equals(audience) || maxBodyBytes <= 0 || maxBodyBytes > AuthzContract.MAX_BODY_BYTES
                || authorityPublicKeys.isEmpty()) throw new IllegalStateException("Invalid Authz ingress configuration");
    }
}
