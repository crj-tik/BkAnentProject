package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Milvus 连接配置。
 */
@ConfigurationProperties(prefix = "milvus")
public class MilvusConnectionProperties {

    /**
     * 业务属性：enabled。
     */
    private boolean enabled;
    /**
     * 业务属性：endpoint。
     */
    private String endpoint;
    /**
     * 业务属性：token。
     */
    private String token;
    /**
     * 业务属性：database。
     */
    private String database;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }
}
