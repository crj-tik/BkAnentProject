package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 报告持久化任务：租约模式（列契约对齐 agent_async_task）。
 */
@TableName("interview_report_task")
public class InterviewReportTaskEntity extends BaseEntity {

    private Long caseId;
    private Long sessionId;
    private Long assetId;
    private String taskType;
    /** PENDING|RUNNING|SUCCEEDED|RETRYABLE|BLOCKED */
    private String status;
    /** 幂等：相同输入快照复用同一任务 */
    private String inputSnapshotHash;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    /** 防旧执行复活 */
    private Integer leaseEpoch;
    private Integer retries;
    private Integer maxRetries;
    /** retryable|blocked */
    private String errorClass;
    private String errorMessage;
    private Long reportId;

    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getInputSnapshotHash() { return inputSnapshotHash; }
    public void setInputSnapshotHash(String inputSnapshotHash) { this.inputSnapshotHash = inputSnapshotHash; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public LocalDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(LocalDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public Integer getLeaseEpoch() { return leaseEpoch; }
    public void setLeaseEpoch(Integer leaseEpoch) { this.leaseEpoch = leaseEpoch; }
    public Integer getRetries() { return retries; }
    public void setRetries(Integer retries) { this.retries = retries; }
    public Integer getMaxRetries() { return maxRetries; }
    public void setMaxRetries(Integer maxRetries) { this.maxRetries = maxRetries; }
    public String getErrorClass() { return errorClass; }
    public void setErrorClass(String errorClass) { this.errorClass = errorClass; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
}
