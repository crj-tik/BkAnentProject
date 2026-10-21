package com.bkanent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Fail-closed experience intake, processing and selection limits. */
@Component
@ConfigurationProperties("memory.v2.experience")
public class MemoryExperienceProperties {
    private boolean enabled;
    private boolean processingEnabled;
    private int batchSize = 4;
    private int maxAttempts = 4;
    private int leaseSeconds = 90;
    private int retrySeconds = 30;
    private int maxCandidates = 128;
    private int maxTokenBudget = 2048;
    private int halfLifeDays = 180;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isProcessingEnabled() { return processingEnabled; }
    public void setProcessingEnabled(boolean value) { processingEnabled = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = bounded(value, 1, 16); }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = bounded(value, 2, 10); }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int value) { leaseSeconds = bounded(value, 60, 300); }
    public int getRetrySeconds() { return retrySeconds; }
    public void setRetrySeconds(int value) { retrySeconds = bounded(value, 1, 3600); }
    public int getMaxCandidates() { return maxCandidates; }
    public void setMaxCandidates(int value) { maxCandidates = bounded(value, 1, 500); }
    public int getMaxTokenBudget() { return maxTokenBudget; }
    public void setMaxTokenBudget(int value) { maxTokenBudget = bounded(value, 1, 4096); }
    public int getHalfLifeDays() { return halfLifeDays; }
    public void setHalfLifeDays(int value) { halfLifeDays = bounded(value, 1, 3650); }

    private static int bounded(int value, int low, int high) {
        if (value < low || value > high) throw new IllegalArgumentException("experience capacity outside allowed range");
        return value;
    }
}
