package com.bkanent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Deployment-owned memory v2 callers; no caller is trusted unless explicitly configured. */
@ConfigurationProperties(prefix = "memory.v2")
public class MemoryV2SecurityProperties {
    private String currentUserPermission = "agent.chat.use";
    private Map<String, TrustedCaller> trustedCallers = new LinkedHashMap<>();

    public String getCurrentUserPermission() { return currentUserPermission; }
    public void setCurrentUserPermission(String currentUserPermission) { this.currentUserPermission = currentUserPermission; }
    public Map<String, TrustedCaller> getTrustedCallers() { return trustedCallers; }
    public void setTrustedCallers(Map<String, TrustedCaller> trustedCallers) {
        this.trustedCallers = trustedCallers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(trustedCallers);
    }

    public static class TrustedCaller {
        private String keyId;
        private String signingSecret;
        private boolean serviceDelivery;
        public boolean isServiceDelivery() { return serviceDelivery; }
        public void setServiceDelivery(boolean serviceDelivery) { this.serviceDelivery = serviceDelivery; }
        /** Exact domain-to-agent allowlist; no cross-product expansion is performed. */
        private Map<String, Set<String>> allowedAgentsByDomain = new LinkedHashMap<>();

        public String getKeyId() { return keyId; }
        public void setKeyId(String keyId) { this.keyId = keyId; }
        public String getSigningSecret() { return signingSecret; }
        public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
        public Map<String, Set<String>> getAllowedAgentsByDomain() { return allowedAgentsByDomain; }
        public void setAllowedAgentsByDomain(Map<String, Set<String>> allowedAgentsByDomain) {
            this.allowedAgentsByDomain = new LinkedHashMap<>();
            if (allowedAgentsByDomain != null) {
                allowedAgentsByDomain.forEach((domain, agents) -> this.allowedAgentsByDomain.put(
                        domain, agents == null ? Set.of() : new LinkedHashSet<>(agents)));
            }
        }

        public boolean allows(String domainId, String agentId) {
            return allowedAgentsByDomain.getOrDefault(domainId, Set.of()).contains(agentId);
        }
    }
}
