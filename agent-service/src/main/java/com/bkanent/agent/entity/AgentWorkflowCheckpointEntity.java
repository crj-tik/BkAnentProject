package com.bkanent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * AgentWorkflowCheckpointEntity 工作流检查点实体。
 */
@TableName("agent_workflow_checkpoint")
public class AgentWorkflowCheckpointEntity extends BaseEntity {

    private String taskId;
    private String sessionId;
    private String traceId;
    private String workflowStatus;
    private String selectedAgentId;
    private String pendingApprovalId;
    private String snapshotJson;
    private Integer checkpointVersion;

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

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getWorkflowStatus() {
        return workflowStatus;
    }

    public void setWorkflowStatus(String workflowStatus) {
        this.workflowStatus = workflowStatus;
    }

    public String getSelectedAgentId() {
        return selectedAgentId;
    }

    public void setSelectedAgentId(String selectedAgentId) {
        this.selectedAgentId = selectedAgentId;
    }

    public String getPendingApprovalId() {
        return pendingApprovalId;
    }

    public void setPendingApprovalId(String pendingApprovalId) {
        this.pendingApprovalId = pendingApprovalId;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }

    public Integer getCheckpointVersion() {
        return checkpointVersion;
    }

    public void setCheckpointVersion(Integer checkpointVersion) {
        this.checkpointVersion = checkpointVersion;
    }
}
