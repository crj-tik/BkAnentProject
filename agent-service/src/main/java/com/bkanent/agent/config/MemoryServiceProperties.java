package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.memory")
public class MemoryServiceProperties {

    private String baseUrl = "http://127.0.0.1:9010";
    private String sessionUpsertPath = "/internal/memory/sessions/upsert";
    private String sessionQueryPath = "/internal/memory/sessions";
    private String artifactCreatePath = "/internal/memory/artifacts";
    private String artifactQueryByTaskPath = "/internal/memory/artifacts/by-task";
    private String artifactQueryByIdPath = "/internal/memory/artifacts/by-id";
    private String handoffCreatePath = "/internal/memory/handoffs";
    private String handoffQueryByTaskPath = "/internal/memory/handoffs/by-task";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSessionUpsertPath() {
        return sessionUpsertPath;
    }

    public void setSessionUpsertPath(String sessionUpsertPath) {
        this.sessionUpsertPath = sessionUpsertPath;
    }

    public String getSessionQueryPath() {
        return sessionQueryPath;
    }

    public void setSessionQueryPath(String sessionQueryPath) {
        this.sessionQueryPath = sessionQueryPath;
    }

    public String getArtifactCreatePath() {
        return artifactCreatePath;
    }

    public void setArtifactCreatePath(String artifactCreatePath) {
        this.artifactCreatePath = artifactCreatePath;
    }

    public String getArtifactQueryByTaskPath() {
        return artifactQueryByTaskPath;
    }

    public void setArtifactQueryByTaskPath(String artifactQueryByTaskPath) {
        this.artifactQueryByTaskPath = artifactQueryByTaskPath;
    }

    public String getArtifactQueryByIdPath() {
        return artifactQueryByIdPath;
    }

    public void setArtifactQueryByIdPath(String artifactQueryByIdPath) {
        this.artifactQueryByIdPath = artifactQueryByIdPath;
    }

    public String getHandoffCreatePath() {
        return handoffCreatePath;
    }

    public void setHandoffCreatePath(String handoffCreatePath) {
        this.handoffCreatePath = handoffCreatePath;
    }

    public String getHandoffQueryByTaskPath() {
        return handoffQueryByTaskPath;
    }

    public void setHandoffQueryByTaskPath(String handoffQueryByTaskPath) {
        this.handoffQueryByTaskPath = handoffQueryByTaskPath;
    }
}
