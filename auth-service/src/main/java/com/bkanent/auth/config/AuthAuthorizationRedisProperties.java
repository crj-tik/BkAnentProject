package com.bkanent.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in only. Registration and a dedicated ACL/TLS writer connection belong to deployment wiring. */
@ConfigurationProperties(prefix = "auth.authorization.redis")
public final class AuthAuthorizationRedisProperties {
    private boolean enabled;
    private boolean protectedWriterConfigured;
    private String namespace = "authz";
    private String channel = "authz:versions";
    private int outboxBatch = 20;
    private int maxAttempts = 3;
    private int maxUsersPerEvent = 1000;
    private int maxEventBytes = 262144;
    private int maxActiveActors = 1000;
    private int renewalBatch = 100;
    private long snapshotTtlSeconds = 600;

    public void validateEnabled() {
        if (!enabled || !protectedWriterConfigured || !namespace.matches("[a-zA-Z0-9_-]{1,40}")
                || !channel.matches("[a-zA-Z0-9:_-]{1,96}") || outboxBatch < 1 || outboxBatch > 100
                || maxAttempts < 1 || maxAttempts > 10 || maxUsersPerEvent < 1 || maxUsersPerEvent > 10000
                || maxEventBytes < 1024 || maxEventBytes > 1048576 || maxActiveActors < 1
                || maxActiveActors > 10000 || renewalBatch < 1 || renewalBatch > 100
                || renewalBatch > maxActiveActors || snapshotTtlSeconds < 30 || snapshotTtlSeconds > 86400) {
            throw new IllegalStateException("Protected authorization Redis writer is not safely configured");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isProtectedWriterConfigured() { return protectedWriterConfigured; }
    public void setProtectedWriterConfigured(boolean value) { protectedWriterConfigured = value; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String value) { namespace = value; }
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel = value; }
    public int getOutboxBatch() { return outboxBatch; }
    public void setOutboxBatch(int value) { outboxBatch = value; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int value) { maxAttempts = value; }
    public int getMaxUsersPerEvent() { return maxUsersPerEvent; }
    public void setMaxUsersPerEvent(int value) { maxUsersPerEvent = value; }
    public int getMaxEventBytes() { return maxEventBytes; }
    public void setMaxEventBytes(int value) { maxEventBytes = value; }
    public int getMaxActiveActors() { return maxActiveActors; }
    public void setMaxActiveActors(int value) { maxActiveActors = value; }
    public int getRenewalBatch() { return renewalBatch; }
    public void setRenewalBatch(int value) { renewalBatch = value; }
    public long getSnapshotTtlSeconds() { return snapshotTtlSeconds; }
    public void setSnapshotTtlSeconds(long value) { snapshotTtlSeconds = value; }
}
