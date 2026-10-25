package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dedicated Redis ACL identity; server ACL grants only namespace GET/MGET and channel SUBSCRIBE. */
@ConfigurationProperties(prefix = "agent.authorization.redis")
public class AgentAuthorizationRedisProperties {
    private String host;
    private int port = 6379;
    private String username;
    private String password;
    private boolean tls;
    private String namespace = "authz";
    private String channel = "authz:versions";

    public void validateTransport(boolean distributed) {
        if (host == null || host.isBlank() || host.contains("://") || host.contains("/")
                || port <= 0 || port > 65535 || username == null || username.isBlank()
                || "default".equalsIgnoreCase(username) || password == null || password.isBlank()
                || namespace == null || !namespace.matches("[a-zA-Z0-9_-]{1,40}")
                || channel == null || !channel.matches("[a-zA-Z0-9:_-]{1,96}") || distributed && !tls) {
            throw new IllegalStateException("Dedicated protected read-only authorization Redis configuration required");
        }
    }
    public String getHost() { return host; }
    public void setHost(String value) { host = value; }
    public int getPort() { return port; }
    public void setPort(int value) { port = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getPassword() { return password; }
    public void setPassword(String value) { password = value; }
    public boolean isTls() { return tls; }
    public void setTls(boolean value) { tls = value; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String value) { namespace = value; }
    public String getChannel() { return channel; }
    public void setChannel(String value) { channel = value; }
    @Override public String toString() { return "AgentAuthorizationRedisProperties[REDACTED]"; }
}
