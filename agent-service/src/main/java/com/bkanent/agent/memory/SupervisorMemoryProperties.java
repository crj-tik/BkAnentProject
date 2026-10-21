package com.bkanent.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("agent.memory-v2")
public class SupervisorMemoryProperties {
    private boolean enabled;
    private boolean compressionEnabled;
    private String keyId = "agent-memory-current";
    private String signingSecret;
    private int cacheEntries = 128;
    private long cacheBytes = 32 * 1024 * 1024;
    private long cacheTtlMs = 60_000;
    private int softTokens = 8000;
    private int hardTokens = 12000;
    private int recentMessages = 12;
    private long summaryTimeoutMs = 5000;
    private long cleanupMs = 30_000;
    private int toolInlineChars = 4096;
    private int recallChars = 8192;
    private int preferenceBudgetTokens = 800;
    private int experienceBudgetTokens = 512;

    public void validate() {
        if (keyId == null || keyId.isBlank() || keyId.length() > 128
                || cacheEntries < 1 || cacheEntries > 10_000
                || cacheBytes < 1024 || cacheBytes > 256L * 1024 * 1024
                || cacheTtlMs < 1 || cacheTtlMs > 300_000
                || softTokens < 1 || hardTokens <= softTokens || hardTokens > 1_000_000
                || recentMessages < 1 || recentMessages > 500
                || summaryTimeoutMs < 1 || summaryTimeoutMs > 20_000
                || cleanupMs < 1_000 || cleanupMs > 300_000
                || toolInlineChars < 256 || toolInlineChars > 16384 || recallChars < 2048 || recallChars > 16384
                || preferenceBudgetTokens < 1 || preferenceBudgetTokens > 4096
                || experienceBudgetTokens < 1 || experienceBudgetTokens > 4096) {
            throw new IllegalArgumentException("invalid agent.memory-v2 bounds");
        }
        if (enabled && (signingSecret == null || signingSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)) {
            throw new IllegalArgumentException("agent.memory-v2.signing-secret must contain at least 32 UTF-8 bytes when enabled");
        }
        if (compressionEnabled && !enabled) {
            throw new IllegalArgumentException("agent.memory-v2.compression-enabled requires agent.memory-v2.enabled");
        }
    }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
    public boolean isCompressionEnabled() { return compressionEnabled; } public void setCompressionEnabled(boolean v) { compressionEnabled = v; }
    public String getKeyId() { return keyId; } public void setKeyId(String v) { keyId = v; }
    public String getSigningSecret() { return signingSecret; } public void setSigningSecret(String v) { signingSecret = v; }
    public int getCacheEntries() { return cacheEntries; } public void setCacheEntries(int v) { cacheEntries = v; }
    public long getCacheBytes() { return cacheBytes; } public void setCacheBytes(long v) { cacheBytes = v; }
    public long getCacheTtlMs() { return cacheTtlMs; } public void setCacheTtlMs(long v) { cacheTtlMs = v; }
    public int getSoftTokens() { return softTokens; } public void setSoftTokens(int v) { softTokens = v; }
    public int getHardTokens() { return hardTokens; } public void setHardTokens(int v) { hardTokens = v; }
    public int getRecentMessages() { return recentMessages; } public void setRecentMessages(int v) { recentMessages = v; }
    public long getSummaryTimeoutMs() { return summaryTimeoutMs; } public void setSummaryTimeoutMs(long v) { summaryTimeoutMs = v; }
    public long getCleanupMs() { return cleanupMs; } public void setCleanupMs(long v) { cleanupMs = v; }
    public int getToolInlineChars() { return toolInlineChars; } public void setToolInlineChars(int v) { toolInlineChars = v; }
    public int getRecallChars() { return recallChars; } public void setRecallChars(int v) { recallChars = v; }
    public int getPreferenceBudgetTokens() { return preferenceBudgetTokens; } public void setPreferenceBudgetTokens(int v) { preferenceBudgetTokens = v; }
    public int getExperienceBudgetTokens() { return experienceBudgetTokens; } public void setExperienceBudgetTokens(int v) { experienceBudgetTokens = v; }
}
