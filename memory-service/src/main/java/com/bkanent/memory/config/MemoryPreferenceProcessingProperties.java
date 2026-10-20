package com.bkanent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

/** Deployment-owned SERVICE processing authority, independent of a user read credential. */
@Component
@ConfigurationProperties("memory.v2.preference.processing")
public class MemoryPreferenceProcessingProperties {
    public enum Mode { SHADOW, PUBLISH }
    private boolean enabled;
    private Mode mode = Mode.SHADOW;
    private int batchSize = 4;
    private int maxAttempts = 4;
    private int leaseSeconds = 90;
    private int retrySeconds = 30;
    private Map<String, Set<String>> allowedAgentsByDomain = new LinkedHashMap<>();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Mode getMode() { return mode; }
    public void setMode(Mode value) { mode = Objects.requireNonNull(value); }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = bounded(value, 1, 16); }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = bounded(value, 2, 10); }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int value) { leaseSeconds = bounded(value, 60, 300); }
    public int getRetrySeconds() { return retrySeconds; }
    public void setRetrySeconds(int value) { retrySeconds = bounded(value, 1, 3600); }
    public Map<String, Set<String>> getAllowedAgentsByDomain() { return allowedAgentsByDomain; }
    public void setAllowedAgentsByDomain(Map<String, Set<String>> value) {
        allowedAgentsByDomain = new LinkedHashMap<>();
        if (value != null) value.forEach((domain, agents) -> allowedAgentsByDomain.put(domain, Set.copyOf(agents)));
    }
    public boolean allows(String domain, String agent) { return allowedAgentsByDomain.getOrDefault(domain, Set.of()).contains(agent); }
    private static int bounded(int value, int low, int high) {
        if (value < low || value > high) throw new IllegalArgumentException("preference processing capacity outside allowed range");
        return value;
    }
}
