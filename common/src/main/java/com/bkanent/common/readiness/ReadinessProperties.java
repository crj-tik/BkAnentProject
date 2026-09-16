package com.bkanent.common.readiness;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dependency checks used by the distributed readiness endpoint.
 */
@ConfigurationProperties(prefix = "service.readiness")
public class ReadinessProperties {

    private boolean enabled = true;
    private String mode = "local";
    private String requiredDependencies = "";
    private final Map<String, String> endpoints = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getRequiredDependencies() {
        return requiredDependencies;
    }

    public void setRequiredDependencies(String requiredDependencies) {
        this.requiredDependencies = requiredDependencies;
    }

    public Map<String, String> getEndpoints() {
        return endpoints;
    }
}
