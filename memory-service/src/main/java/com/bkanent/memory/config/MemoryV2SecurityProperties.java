package com.bkanent.memory.config;

import com.bkanent.common.security.ServiceIdentityCrypto;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Deployment-owned memory v2 callers; no caller is trusted unless explicitly configured. */
@ConfigurationProperties(prefix = "memory.v2")
public class MemoryV2SecurityProperties {
    private String currentUserPermission = "agent.chat.use";
    private String experienceGovernancePermission = "memory.experience.governance.publish";
    private boolean authorizationContextEnabled;
    private Map<String, String> authzAuthorityPublicKeys = new LinkedHashMap<>();
    private Map<String, TrustedCaller> trustedCallers = new LinkedHashMap<>();

    @PostConstruct
    public void validate() {
        if (!authorizationContextEnabled) return;
        authorizationAuthorityKeys();
        if (!"agent.chat.use".equals(currentUserPermission)) {
            throw new IllegalStateException("Signed Memory authorization contexts require the Agent chat audience permission");
        }
    }

    public String getCurrentUserPermission() { return currentUserPermission; }
    public void setCurrentUserPermission(String currentUserPermission) { this.currentUserPermission = currentUserPermission; }
    public String getExperienceGovernancePermission() { return experienceGovernancePermission; }
    public void setExperienceGovernancePermission(String experienceGovernancePermission) {
        this.experienceGovernancePermission = experienceGovernancePermission;
    }
    public Map<String, TrustedCaller> getTrustedCallers() { return trustedCallers; }
    public void setTrustedCallers(Map<String, TrustedCaller> trustedCallers) {
        this.trustedCallers = trustedCallers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(trustedCallers);
    }
    public boolean isAuthorizationContextEnabled() { return authorizationContextEnabled; }
    public void setAuthorizationContextEnabled(boolean value) { authorizationContextEnabled = value; }
    public Map<String, String> getAuthzAuthorityPublicKeys() { return authzAuthorityPublicKeys; }
    public void setAuthzAuthorityPublicKeys(Map<String, String> value) {
        authzAuthorityPublicKeys = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
    }

    public Map<String, PublicKey> authorizationAuthorityKeys() {
        try {
            if (authzAuthorityPublicKeys == null || authzAuthorityPublicKeys.isEmpty()) throw new IllegalArgumentException();
            Map<String, PublicKey> keys = new LinkedHashMap<>();
            authzAuthorityPublicKeys.forEach((id, encoded) -> {
                if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
                        || encoded == null || encoded.isBlank()) throw new IllegalArgumentException();
                keys.put(id, ServiceIdentityCrypto.decodePublicKey(encoded));
            });
            return Map.copyOf(keys);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid Memory authorization authority registration");
        }
    }

    public static class TrustedCaller {
        /** Ed25519 public keys keyed by keyId; this is verifier-only configuration. */
        private Map<String, String> publicKeys = new LinkedHashMap<>();
        private boolean serviceDelivery;
        private boolean experienceOutcomePublisher;
        private boolean experienceGovernancePublisher;
        public boolean isServiceDelivery() { return serviceDelivery; }
        public void setServiceDelivery(boolean serviceDelivery) { this.serviceDelivery = serviceDelivery; }
        public boolean isExperienceOutcomePublisher() { return experienceOutcomePublisher; }
        public void setExperienceOutcomePublisher(boolean experienceOutcomePublisher) { this.experienceOutcomePublisher = experienceOutcomePublisher; }
        public boolean isExperienceGovernancePublisher() { return experienceGovernancePublisher; }
        public void setExperienceGovernancePublisher(boolean experienceGovernancePublisher) {
            this.experienceGovernancePublisher = experienceGovernancePublisher;
        }
        /** Exact domain-to-agent allowlist; no cross-product expansion is performed. */
        private Map<String, Set<String>> allowedAgentsByDomain = new LinkedHashMap<>();

        public Map<String, String> getPublicKeys() { return publicKeys; }
        public void setPublicKeys(Map<String, String> publicKeys) {
            this.publicKeys = publicKeys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(publicKeys);
        }
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
