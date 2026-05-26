package com.bkanent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

@TableName("agent_event_audit")
public class AgentEventAuditEntity extends BaseEntity {

    private String sessionId;
    private String taskId;
    private String agentId;
    private String eventType;
    private String stage;
    private String content;
    private String metadataJson;
    private String traceId;
    private String approvalId;
    private String artifactId;
    private String asyncTaskId;
    private String asyncWorkflowId;
    private String grayStrategyVersion;
    private Long eventTimestamp;
    private Integer archived;
    private Long archivedAtMs;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(String approvalId) {
        this.approvalId = approvalId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getAsyncTaskId() {
        return asyncTaskId;
    }

    public void setAsyncTaskId(String asyncTaskId) {
        this.asyncTaskId = asyncTaskId;
    }

    public String getAsyncWorkflowId() {
        return asyncWorkflowId;
    }

    public void setAsyncWorkflowId(String asyncWorkflowId) {
        this.asyncWorkflowId = asyncWorkflowId;
    }

    public String getGrayStrategyVersion() {
        return grayStrategyVersion;
    }

    public void setGrayStrategyVersion(String grayStrategyVersion) {
        this.grayStrategyVersion = grayStrategyVersion;
    }

    public Long getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(Long eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public Integer getArchived() {
        return archived;
    }

    public void setArchived(Integer archived) {
        this.archived = archived;
    }

    public Long getArchivedAtMs() {
        return archivedAtMs;
    }

    public void setArchivedAtMs(Long archivedAtMs) {
        this.archivedAtMs = archivedAtMs;
    }
}
