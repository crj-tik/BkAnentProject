package com.bkanent.auth.config;

import com.bkanent.common.security.ServiceIdentityCrypto;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "auth.authorization")
public class AuthAuthorizationProperties {
    private boolean enabled;
    private String responseKeyId = "auth-service-current";
    private String privateKeyFile = "";
    private String responsePublicKey = "";
    private Map<String, TrustedCaller> trustedCallers = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getResponseKeyId() { return responseKeyId; }
    public void setResponseKeyId(String responseKeyId) { this.responseKeyId = responseKeyId; }
    public String getPrivateKeyFile() { return privateKeyFile; }
    public void setPrivateKeyFile(String privateKeyFile) { this.privateKeyFile = privateKeyFile; }
    public String getResponsePublicKey() { return responsePublicKey; }
    public void setResponsePublicKey(String responsePublicKey) { this.responsePublicKey = responsePublicKey; }
    public Map<String, TrustedCaller> getTrustedCallers() { return trustedCallers; }
    public void setTrustedCallers(Map<String, TrustedCaller> trustedCallers) {
        this.trustedCallers = trustedCallers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(trustedCallers);
    }

    public PrivateKey signingKey() {
        if (privateKeyFile == null || privateKeyFile.isBlank()) {
            throw new IllegalStateException("Authorization signing identity is not configured");
        }
        return ServiceIdentityCrypto.loadPrivateKey(java.nio.file.Path.of(privateKeyFile));
    }

    public static class TrustedCaller {
        private String publicKey = "";
        private String service = "";
        private Set<String> operations = new LinkedHashSet<>();
        private Set<String> audiences = new LinkedHashSet<>();
        private Set<String> permissionCodes = new LinkedHashSet<>();

        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public Set<String> getOperations() { return operations; }
        public void setOperations(Set<String> operations) { this.operations = copy(operations); }
        public Set<String> getAudiences() { return audiences; }
        public void setAudiences(Set<String> audiences) { this.audiences = copy(audiences); }
        public Set<String> getPermissionCodes() { return permissionCodes; }
        public void setPermissionCodes(Set<String> permissionCodes) { this.permissionCodes = copy(permissionCodes); }
        private static Set<String> copy(Set<String> values) {
            return values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
        }
    }
}
