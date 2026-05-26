package com.bkanent.memory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

@TableName("handoff_relation_memory")
public class HandoffRelationMemoryEntity extends BaseEntity {

    private String handoffId;
    private String sessionId;
    private String taskId;
    private String parentTaskId;
    private String traceId;
    private String fromAgent;
    private String toAgent;
    private String reason;
    private String userGoal;
    private String structuredContextJson;
    private String artifactIdsJson;
    private String constraintsJson;
    private String expectedOutput;

    public String getHandoffId() { return handoffId; }
    public void setHandoffId(String handoffId) { this.handoffId = handoffId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getParentTaskId() { return parentTaskId; }
    public void setParentTaskId(String parentTaskId) { this.parentTaskId = parentTaskId; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getFromAgent() { return fromAgent; }
    public void setFromAgent(String fromAgent) { this.fromAgent = fromAgent; }
    public String getToAgent() { return toAgent; }
    public void setToAgent(String toAgent) { this.toAgent = toAgent; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getUserGoal() { return userGoal; }
    public void setUserGoal(String userGoal) { this.userGoal = userGoal; }
    public String getStructuredContextJson() { return structuredContextJson; }
    public void setStructuredContextJson(String structuredContextJson) { this.structuredContextJson = structuredContextJson; }
    public String getArtifactIdsJson() { return artifactIdsJson; }
    public void setArtifactIdsJson(String artifactIdsJson) { this.artifactIdsJson = artifactIdsJson; }
    public String getConstraintsJson() { return constraintsJson; }
    public void setConstraintsJson(String constraintsJson) { this.constraintsJson = constraintsJson; }
    public String getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(String expectedOutput) { this.expectedOutput = expectedOutput; }
}
