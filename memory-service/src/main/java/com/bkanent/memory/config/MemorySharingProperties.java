package com.bkanent.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Durable cross-agent memory publishing remains opt-in and bounded. */
@Component
@ConfigurationProperties(prefix = "memory.v2.sharing")
public class MemorySharingProperties {
    private boolean enabled;
    private int maxExportUtf8Bytes = 24576;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxExportUtf8Bytes() { return maxExportUtf8Bytes; }
    public void setMaxExportUtf8Bytes(int value) {
        if (value < 1024 || value > 24576) throw new IllegalArgumentException("maxExportUtf8Bytes");
        maxExportUtf8Bytes = value;
    }
}
