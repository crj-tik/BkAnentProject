package com.bkanent.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG-only service credentials, supplied by environment/secret-managed deployment configuration.
 * Incoming keys identify explicitly managed RAG/domain callers; application names and IDs grant nothing.
 * Keep multiple key IDs during rotation, then remove the retired ID to revoke it.
 */
@Component
@ConfigurationProperties(prefix = "auth.rag.rpc")
public class AuthRagRpcProperties {

    private Map<String, TrustedKey> trustedKeys = new LinkedHashMap<>();
    private String responseKeyId;
    private String responseSecret;

    public Map<String, TrustedKey> getTrustedKeys() {
        return trustedKeys;
    }

    public void setTrustedKeys(Map<String, TrustedKey> trustedKeys) {
        this.trustedKeys = trustedKeys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(trustedKeys);
    }

    public static class TrustedKey {
        private String callerService;
        private String secret;

        public String getCallerService() {
            return callerService;
        }

        public void setCallerService(String callerService) {
            this.callerService = callerService;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        @Override
        public String toString() {
            return "TrustedKey[credentials=REDACTED]";
        }
    }

    public String getResponseKeyId() {
        return responseKeyId;
    }

    public void setResponseKeyId(String responseKeyId) {
        this.responseKeyId = responseKeyId;
    }

    public String getResponseSecret() {
        return responseSecret;
    }

    public void setResponseSecret(String responseSecret) {
        this.responseSecret = responseSecret;
    }

    @Override
    public String toString() {
        return "AuthRagRpcProperties[credentials=REDACTED]";
    }
}
