package com.bkanent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hot-toggled worker gate: the flag is re-read on every scheduled tick, so a Nacos refresh
 * pauses scheduling without a restart and the in-flight batch drains undisturbed.
 */
@ConfigurationProperties(prefix = "rag.index.worker")
public class RagIndexSchedulingProperties {
    private boolean enabled = false;
    private int batchSize = 20;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
