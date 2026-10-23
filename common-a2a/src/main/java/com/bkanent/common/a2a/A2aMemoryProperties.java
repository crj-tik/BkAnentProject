package com.bkanent.common.a2a;

import org.springframework.boot.context.properties.ConfigurationProperties;
import com.bkanent.common.security.ServiceIdentityCrypto;

import java.nio.file.Path;
import java.security.PrivateKey;

/** Deployment-owned bounds and required identity for the domain A2A memory adapter. */
@ConfigurationProperties(prefix = "a2a.memory")
public class A2aMemoryProperties {
    private boolean compressionEnabled;
    private String keyId = "domain-memory-current";
    private String privateKeyFile = "";
    private volatile PrivateKey signingKey;
    private int cacheEntries = 64;
    private long cacheBytes = 16L * 1024 * 1024;
    private long cacheTtlMs = 60_000;
    private int softTokens = 8_000;
    private int hardTokens = 12_000;
    private int recentMessages = 12;
    private int summaryTimeoutMs = 5_000;
    private int maxSummaryPromptChars = 64_000;

    public boolean isCompressionEnabled() { return compressionEnabled; }
    public void setCompressionEnabled(boolean compressionEnabled) { this.compressionEnabled = compressionEnabled; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getPrivateKeyFile() { return privateKeyFile; }
    public void setPrivateKeyFile(String privateKeyFile) { this.privateKeyFile = privateKeyFile; this.signingKey = null; }
    public PrivateKey signingKey() {
        PrivateKey key = signingKey;
        if (key != null) return key;
        if (privateKeyFile == null || privateKeyFile.isBlank()) throw new IllegalArgumentException("service identity private key is not configured");
        synchronized (this) {
            if (signingKey == null) signingKey = ServiceIdentityCrypto.loadPrivateKey(Path.of(privateKeyFile));
            return signingKey;
        }
    }
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
        if (keyId == null || keyId.isBlank() || privateKeyFile == null || privateKeyFile.isBlank()) {
            throw new IllegalArgumentException("A2A memory requires a service identity key id and private key file");
        }
    }
}
