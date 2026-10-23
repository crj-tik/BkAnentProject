package com.bkanent.auth.config;

import com.bkanent.common.rag.RagOperation;
import com.bkanent.common.security.ServiceIdentityCrypto;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Auth's A2A trust registry. It holds caller public keys and its own mounted private identity key. */
@Component
@ConfigurationProperties(prefix = "auth.a2a")
public class AuthA2aIdentityProperties {
    private Map<String, TrustedIdentity> trustedIdentities = new LinkedHashMap<>();
    private String responseKeyId;
    private String responsePrivateKeyFile;
    private volatile PrivateKey responseSigningKey;

    public Map<String, TrustedIdentity> getTrustedIdentities() { return trustedIdentities; }
    public void setTrustedIdentities(Map<String, TrustedIdentity> trustedIdentities) {
        this.trustedIdentities = trustedIdentities == null ? new LinkedHashMap<>() : new LinkedHashMap<>(trustedIdentities);
    }
    public String getResponseKeyId() { return responseKeyId; }
    public void setResponseKeyId(String responseKeyId) { this.responseKeyId = responseKeyId; }
    public String getResponsePrivateKeyFile() { return responsePrivateKeyFile; }
    public void setResponsePrivateKeyFile(String responsePrivateKeyFile) {
        this.responsePrivateKeyFile = responsePrivateKeyFile;
        this.responseSigningKey = null;
    }
    public PrivateKey responseSigningKey() {
        PrivateKey key = responseSigningKey;
        if (key != null) return key;
        if (responsePrivateKeyFile == null || responsePrivateKeyFile.isBlank()) {
            throw new IllegalArgumentException("Auth service identity private key is not configured");
        }
        synchronized (this) {
            if (responseSigningKey == null) {
                responseSigningKey = ServiceIdentityCrypto.loadPrivateKey(Path.of(responsePrivateKeyFile));
            }
            return responseSigningKey;
        }
    }

    public static class TrustedIdentity {
        private String callerService;
        private String publicKey;
        private Set<RagOperation> allowedOperations = new LinkedHashSet<>();

        public String getCallerService() { return callerService; }
        public void setCallerService(String callerService) { this.callerService = callerService; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
        public Set<RagOperation> getAllowedOperations() { return allowedOperations; }
        public void setAllowedOperations(Set<RagOperation> allowedOperations) {
            this.allowedOperations = allowedOperations == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedOperations);
        }
        @Override public String toString() { return "TrustedIdentity[publicKey=REDACTED]"; }
    }

    @Override public String toString() { return "AuthA2aIdentityProperties[privateKey=REDACTED]"; }
}
