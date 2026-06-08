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
    private String userPreferenceUpsertPath = "/internal/memory/user-preferences/upsert";
    private String userPreferenceQueryPath = "/internal/memory/user-preferences/{userId}";
    private String userPreferenceDecayPath = "/internal/memory/user-preferences/{userId}/decay";
    private String systemConstraintUpsertPath = "/internal/memory/system-constraints/upsert";
    private String systemConstraintQueryPath = "/internal/memory/system-constraints";
    private String systemConstraintSearchPath = "/internal/memory/system-constraints/search";
    private String sessionSnapshotPath = "/internal/memory/sessions/snapshot";
    private String sessionSnapshotQueryPath = "/internal/memory/sessions/snapshot/{sessionId}";
    private String workflowHistoryPath = "/internal/memory/workflow/{taskId}/history";

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

    public String getUserPreferenceUpsertPath() {
        return userPreferenceUpsertPath;
    }

    public void setUserPreferenceUpsertPath(String userPreferenceUpsertPath) {
        this.userPreferenceUpsertPath = userPreferenceUpsertPath;
    }

    public String getUserPreferenceQueryPath() {
        return userPreferenceQueryPath;
    }

    public void setUserPreferenceQueryPath(String userPreferenceQueryPath) {
        this.userPreferenceQueryPath = userPreferenceQueryPath;
    }

    public String getUserPreferenceDecayPath() {
        return userPreferenceDecayPath;
    }

    public void setUserPreferenceDecayPath(String userPreferenceDecayPath) {
        this.userPreferenceDecayPath = userPreferenceDecayPath;
    }

    public String getSystemConstraintUpsertPath() {
        return systemConstraintUpsertPath;
    }

    public void setSystemConstraintUpsertPath(String systemConstraintUpsertPath) {
        this.systemConstraintUpsertPath = systemConstraintUpsertPath;
    }

    public String getSystemConstraintQueryPath() {
        return systemConstraintQueryPath;
    }

    public void setSystemConstraintQueryPath(String systemConstraintQueryPath) {
        this.systemConstraintQueryPath = systemConstraintQueryPath;
    }

    public String getSystemConstraintSearchPath() {
        return systemConstraintSearchPath;
    }

    public void setSystemConstraintSearchPath(String systemConstraintSearchPath) {
        this.systemConstraintSearchPath = systemConstraintSearchPath;
    }

    public String getSessionSnapshotPath() {
        return sessionSnapshotPath;
    }

    public void setSessionSnapshotPath(String sessionSnapshotPath) {
        this.sessionSnapshotPath = sessionSnapshotPath;
    }

    public String getSessionSnapshotQueryPath() {
        return sessionSnapshotQueryPath;
    }

    public void setSessionSnapshotQueryPath(String sessionSnapshotQueryPath) {
        this.sessionSnapshotQueryPath = sessionSnapshotQueryPath;
    }

    public String getWorkflowHistoryPath() {
        return workflowHistoryPath;
    }

    public void setWorkflowHistoryPath(String workflowHistoryPath) {
        this.workflowHistoryPath = workflowHistoryPath;
    }
}
