package com.bkanent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/** Database-level idempotency claim for an approval resume callback. */
@TableName("agent_workflow_approval_claim")
public class AgentWorkflowApprovalClaimEntity extends BaseEntity {

    private String approvalId;
    private String taskId;
    private String sessionId;
    private Integer approvalVersion;
    private String decisionStatus;
    private String resultJson;
    private String errorMessage;

    public String getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(String approvalId) {
        this.approvalId = approvalId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getApprovalVersion() {
        return approvalVersion;
    }

    public void setApprovalVersion(Integer approvalVersion) {
        this.approvalVersion = approvalVersion;
    }

    public String getDecisionStatus() {
        return decisionStatus;
    }

    public void setDecisionStatus(String decisionStatus) {
        this.decisionStatus = decisionStatus;
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
}
