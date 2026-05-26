package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MilvusConnectionProperties 配置属性类。
 */
@ConfigurationProperties(prefix = "milvus")
public class MilvusConnectionProperties {

    /**
     * 字段：enabled。
     */
    private boolean enabled;

    /**
     * 字段：endpoint。
     */
    private String endpoint;

    /**
     * 字段：token。
     */
    private String token;

    /**
     * 字段：database。
     */
    private String database;

    /**
     * 字段：username。
     */
    private String username = "root";

    /**
     * 字段：password。
     */
    private String password = "Milvus";

    /**
     * 字段：uri。
     */
    private String uri;

    /**
     * 判断是否enabled。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置enabled。
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取endpoint。
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * 设置endpoint。
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * 获取token。
     */
    public String getToken() {
        return token;
    }

    /**
     * 设置token。
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * 获取database。
     */
    public String getDatabase() {
        return database;
    }

    /**
     * 设置database。
     */
    public void setDatabase(String database) {
        this.database = database;
    }

    /**
     * 获取username。
     */
    public String getUsername() {
        return username;
    }

    /**
     * 设置username。
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * 获取password。
     */
    public String getPassword() {
        return password;
    }

    /**
     * 设置password。
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * 获取uri。
     */
    public String getUri() {
        return uri;
    }

    /**
     * 设置uri。
     */
    public void setUri(String uri) {
        this.uri = uri;
    }
}
