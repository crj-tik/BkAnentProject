package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remote MCP server connection properties.
 */
@ConfigurationProperties(prefix = "agent.mcp")
public class AgentMcpProperties {

    private Map<String, String> servers = new LinkedHashMap<>();

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration requestTimeout = Duration.ofSeconds(15);

    private Duration initializationTimeout = Duration.ofSeconds(10);

    public Map<String, String> getServers() {
        return servers;
    }

    public void setServers(Map<String, String> servers) {
        this.servers = servers;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getInitializationTimeout() {
        return initializationTimeout;
    }

    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout;
    }
}
