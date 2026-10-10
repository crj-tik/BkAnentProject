package com.bkanent.common.a2a;

@org.springframework.boot.context.properties.ConfigurationProperties("agent.a2a.execution")
public class A2aExecutionProperties {
    private long streamTimeoutMs = 300_000;
    public long getStreamTimeoutMs() { return streamTimeoutMs; }
    public void setStreamTimeoutMs(long value) {
        if (value < 1) throw new IllegalArgumentException("stream timeout must be positive");
        streamTimeoutMs = value;
    }
}
