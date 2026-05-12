package com.bkanent.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * GatewayCorsProperties 跨域配置属性类。
 */
@ConfigurationProperties(prefix = "gateway.cors")
public class GatewayCorsProperties {

    /**
     * 业务属性：allowedOriginPatterns。
     */
    private List<String> allowedOriginPatterns = new ArrayList<>(List.of("*"));
    /**
     * 业务属性：allowedMethods。
     */
    private List<String> allowedMethods = new ArrayList<>(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    /**
     * 业务属性：allowedHeaders。
     */
    private List<String> allowedHeaders = new ArrayList<>(List.of("*"));
    /**
     * 业务属性：allowCredentials。
     */
    private boolean allowCredentials;
    /**
     * 业务属性：maxAge。
     */
    private Long maxAge = 3600L;

    public List<String> getAllowedOriginPatterns() {
        return allowedOriginPatterns;
    }

    public void setAllowedOriginPatterns(List<String> allowedOriginPatterns) {
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    public List<String> getAllowedMethods() {
        return allowedMethods;
    }

    public void setAllowedMethods(List<String> allowedMethods) {
        this.allowedMethods = allowedMethods;
    }

    public List<String> getAllowedHeaders() {
        return allowedHeaders;
    }

    public void setAllowedHeaders(List<String> allowedHeaders) {
        this.allowedHeaders = allowedHeaders;
    }

    public boolean isAllowCredentials() {
        return allowCredentials;
    }

    public void setAllowCredentials(boolean allowCredentials) {
        this.allowCredentials = allowCredentials;
    }

    public Long getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(Long maxAge) {
        this.maxAge = maxAge;
    }
}
