package com.bkanent.common.a2a;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned bounds and opt-in for the domain A2A memory adapter. */
@ConfigurationProperties(prefix = "a2a.memory")
public class A2aMemoryProperties {
    private boolean enabled;
    private boolean compressionEnabled;
    private String keyId = "domain-memory-current";
    private String signingSecret = "";
    private int cacheEntries = 64;
    private long cacheBytes = 16L * 1024 * 1024;
    private long cacheTtlMs = 60_000;
    private int softTokens = 8_000;
    private int hardTokens = 12_000;
    private int recentMessages = 12;
    private int summaryTimeoutMs = 5_000;
    private int maxSummaryPromptChars = 64_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isCompressionEnabled() { return compressionEnabled; }
    public void setCompressionEnabled(boolean compressionEnabled) { this.compressionEnabled = compressionEnabled; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }
    public int getCacheEntries() { return cacheEntries; }
    public void setCacheEntries(int cacheEntries) { this.cacheEntries = cacheEntries; }
    public long getCacheBytes() { return cacheBytes; }
    public void setCacheBytes(long cacheBytes) { this.cacheBytes = cacheBytes; }
    public long getCacheTtlMs() { return cacheTtlMs; }
    public void setCacheTtlMs(long cacheTtlMs) { this.cacheTtlMs = cacheTtlMs; }
    public int getSoftTokens() { return softTokens; }
    public void setSoftTokens(int softTokens) { this.softTokens = softTokens; }
    public int getHardTokens() { return hardTokens; }
    public void setHardTokens(int hardTokens) { this.hardTokens = hardTokens; }
    public int getRecentMessages() { return recentMessages; }
    public void setRecentMessages(int recentMessages) { this.recentMessages = recentMessages; }
    public int getSummaryTimeoutMs() { return summaryTimeoutMs; }
    public void setSummaryTimeoutMs(int summaryTimeoutMs) { this.summaryTimeoutMs = summaryTimeoutMs; }
    public int getMaxSummaryPromptChars() { return maxSummaryPromptChars; }
    public void setMaxSummaryPromptChars(int maxSummaryPromptChars) { this.maxSummaryPromptChars = maxSummaryPromptChars; }

    public void validate() {
        if (cacheEntries < 1 || cacheEntries > 10_000 || cacheBytes < 1024 || cacheTtlMs < 1 || cacheTtlMs > 300_000
                || softTokens < 1 || hardTokens <= softTokens || hardTokens > 1_000_000
                || recentMessages < 1 || recentMessages > 500 || summaryTimeoutMs < 1 || summaryTimeoutMs > 60_000
                || maxSummaryPromptChars < 1024 || maxSummaryPromptChars > 1_000_000) {
            throw new IllegalArgumentException("invalid A2A memory bounds");
        }
        if (compressionEnabled && !enabled) throw new IllegalArgumentException("A2A memory compression requires memory to be enabled");
        if (enabled && (keyId == null || keyId.isBlank() || signingSecret == null
                || signingSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)) {
            throw new IllegalArgumentException("A2A memory requires a key id and a signing secret of at least 32 bytes");
        }
    }
}
