package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentMcpProperties 配置属性类。
 */
@ConfigurationProperties(prefix = "agent.mcp")
public class AgentMcpProperties {

    private Map<String, String> servers = new LinkedHashMap<>();

    /**
     * 处理ofSeconds。
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * 处理ofSeconds。
     */
    private Duration requestTimeout = Duration.ofSeconds(15);

    /**
     * 处理ofSeconds。
     */
    private Duration initializationTimeout = Duration.ofSeconds(10);

    /**
     * 获取servers。
     */
    public Map<String, String> getServers() {
        return servers;
    }

    /**
     * 设置servers。
     */
    public void setServers(Map<String, String> servers) {
        this.servers = servers == null ? new LinkedHashMap<>() : servers;
    }

    /**
     * 获取connectTimeout。
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * 设置connectTimeout。
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * 获取requestTimeout。
     */
    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * 设置requestTimeout。
     */
    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    /**
     * 获取initializationTimeout。
     */
    public Duration getInitializationTimeout() {
        return initializationTimeout;
    }

    /**
     * 设置initializationTimeout。
     */
    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout;
    }
}
