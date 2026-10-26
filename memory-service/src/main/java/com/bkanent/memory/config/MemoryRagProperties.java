package com.bkanent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
@ConfigurationProperties(prefix = "memory.rag")
public class MemoryRagProperties {
    private String keyId = "memory-current";
    private String signingSecret;
    private String authKeyId;
    private String authSigningSecret;
    private String candidateAuthorizationKeyId;
    private String candidateAuthorizationSecret;
    private String policyVersion = "memory-v1";
    private Map<String, TrustedCaller> trustedCallers = new LinkedHashMap<>();
    private Set<String> constraintReadRoles = new LinkedHashSet<>();
    private Set<String> constraintAdminRoles = new LinkedHashSet<>();
    private Set<String> semanticScoringVersions = new LinkedHashSet<>();
    private boolean dispatcherEnabled;
    /** Writes versioned private memory documents into the registered v2 spaces. Disabled by default. */
    private boolean domainProjectionEnabled;
    /** Uses authorized RAG candidates for memory v2 reads. Dependency failures never fall back to SQL scans. */
    private boolean domainSearchEnabled;
    private boolean versionedAuthorizationRequired;
    private int dispatchBatchSize = 25;
    private int maxAttempts = 5;
    private long retryDelayMs = 1000;
    private long dispatchLeaseMs = 30000;
    public String getKeyId() { return keyId; }
    public void setKeyId(String value) { keyId = value; }
    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String value) { signingSecret = value; }
    public String getAuthKeyId() { return authKeyId; }
    public void setAuthKeyId(String value) { authKeyId = value; }
    public String getAuthSigningSecret() { return authSigningSecret; }
    public void setAuthSigningSecret(String value) { authSigningSecret = value; }
    public String getCandidateAuthorizationKeyId() { return candidateAuthorizationKeyId; }
    public void setCandidateAuthorizationKeyId(String value) { candidateAuthorizationKeyId = value; }
    public String getCandidateAuthorizationSecret() { return candidateAuthorizationSecret; }
    public void setCandidateAuthorizationSecret(String value) { candidateAuthorizationSecret = value; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String value) { policyVersion = value; }
    public Map<String, TrustedCaller> getTrustedCallers() { return trustedCallers; }
    public void setTrustedCallers(Map<String, TrustedCaller> value) { trustedCallers = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value); }
    public Set<String> getConstraintReadRoles() { return constraintReadRoles; }
    public void setConstraintReadRoles(Set<String> value) { constraintReadRoles = value == null ? Set.of() : Set.copyOf(value); }
    public Set<String> getConstraintAdminRoles() { return constraintAdminRoles; }
    public void setConstraintAdminRoles(Set<String> value) { constraintAdminRoles = value == null ? Set.of() : Set.copyOf(value); }
    public Set<String> getSemanticScoringVersions() { return semanticScoringVersions; }
    public void setSemanticScoringVersions(Set<String> value) { semanticScoringVersions = value == null ? Set.of() : Set.copyOf(value); }
    public boolean isDispatcherEnabled() { return dispatcherEnabled; }
    public void setDispatcherEnabled(boolean value) { dispatcherEnabled = value; }
    public boolean isDomainProjectionEnabled() { return domainProjectionEnabled; }
    public void setDomainProjectionEnabled(boolean value) { domainProjectionEnabled = value; }
    public boolean isDomainSearchEnabled() { return domainSearchEnabled; }
    public void setDomainSearchEnabled(boolean value) { domainSearchEnabled = value; }
    public boolean isVersionedAuthorizationRequired() { return versionedAuthorizationRequired; }
    public void setVersionedAuthorizationRequired(boolean value) { versionedAuthorizationRequired = value; }
    public int getDispatchBatchSize() { return dispatchBatchSize; }
    public void setDispatchBatchSize(int value) { if (value < 1 || value > 100) throw new IllegalArgumentException("dispatchBatchSize"); dispatchBatchSize = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { if (value < 1 || value > 5) throw new IllegalArgumentException("maxAttempts"); maxAttempts = value; }
    public long getRetryDelayMs() { return retryDelayMs; }
    public void setRetryDelayMs(long value) { if (value < 1 || value > 60000) throw new IllegalArgumentException("retryDelayMs"); retryDelayMs = value; }
    public long getDispatchLeaseMs() { return dispatchLeaseMs; }
    public void setDispatchLeaseMs(long value) { if (value < 1000 || value > 300000) throw new IllegalArgumentException("dispatchLeaseMs"); dispatchLeaseMs = value; }
    public static class TrustedCaller {
        private String callerService;
        private String secret;
        public String getCallerService() { return callerService; }
        public void setCallerService(String value) { callerService = value; }
        public String getSecret() { return secret; }
        public void setSecret(String value) { secret = value; }
        @Override public String toString() { return "TrustedCaller[REDACTED]"; }
    }
    @Override public String toString() { return "MemoryRagProperties[REDACTED]"; }
}
