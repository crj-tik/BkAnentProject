package com.bkanent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

@TableName("agent_async_task")
public class AgentAsyncTaskEntity extends BaseEntity {

    private String asyncTaskId;
    private String sessionId;
    private String taskId;
    private String traceId;
    private String userId;
    private String selectedAgentId;
    private String mode;
    private String childAsyncTaskId;
    private String status;
    private String resultJson;
    private String errorMessage;
    private String originalRequestJson;
    private Long startedAtMs;
    private Long finishedAtMs;

    public String getAsyncTaskId() {
        return asyncTaskId;
    }

    public void setAsyncTaskId(String asyncTaskId) {
        this.asyncTaskId = asyncTaskId;
    }

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

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSelectedAgentId() {
        return selectedAgentId;
    }

    public void setSelectedAgentId(String selectedAgentId) {
        this.selectedAgentId = selectedAgentId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getChildAsyncTaskId() {
        return childAsyncTaskId;
    }

    public void setChildAsyncTaskId(String childAsyncTaskId) {
        this.childAsyncTaskId = childAsyncTaskId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getOriginalRequestJson() {
        return originalRequestJson;
    }

    public void setOriginalRequestJson(String originalRequestJson) {
        this.originalRequestJson = originalRequestJson;
    }

    public Long getStartedAtMs() {
        return startedAtMs;
    }

    public void setStartedAtMs(Long startedAtMs) {
        this.startedAtMs = startedAtMs;
    }

    public Long getFinishedAtMs() {
        return finishedAtMs;
    }

    public void setFinishedAtMs(Long finishedAtMs) {
        this.finishedAtMs = finishedAtMs;
    }
}
