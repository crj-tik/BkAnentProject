package com.bkanent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

@TableName("agent_async_workflow")
public class AgentAsyncWorkflowEntity extends BaseEntity {

    private String asyncWorkflowId;
    private String sessionId;
    private String taskId;
    private String traceId;
    private String userId;
    private String status;
    private String resultJson;
    private String errorMessage;
    private String originalRequestJson;
    private Integer cancelRequested;
    private Long startedAtMs;
    private Long finishedAtMs;

    public String getAsyncWorkflowId() {
        return asyncWorkflowId;
    }

    public void setAsyncWorkflowId(String asyncWorkflowId) {
        this.asyncWorkflowId = asyncWorkflowId;
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

    public Integer getCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(Integer cancelRequested) {
        this.cancelRequested = cancelRequested;
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
