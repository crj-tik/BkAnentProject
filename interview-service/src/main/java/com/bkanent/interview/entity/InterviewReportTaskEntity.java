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
    /** Database-enforced idempotency key; nullable on preserved legacy tasks. */
    private String idempotencyKey;
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
    private String sourceContentHash;
    private Integer sourceRevision;
    private Integer sourceSanitizationRevision;
    private String templateVersion;
    private Long requestedByUserId;
    private Long permissionRevision;
    private Long credentialEpoch;
    private LocalDateTime authorizationExpiresAt;
    private Boolean businessWide;

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
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { idempotencyKey = value; }
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
    public String getSourceContentHash() { return sourceContentHash; }
    public void setSourceContentHash(String value) { sourceContentHash = value; }
    public Integer getSourceRevision() { return sourceRevision; }
    public void setSourceRevision(Integer value) { sourceRevision = value; }
    public Integer getSourceSanitizationRevision() { return sourceSanitizationRevision; }
    public void setSourceSanitizationRevision(Integer value) { sourceSanitizationRevision = value; }
    public String getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(String value) { templateVersion = value; }
    public Long getRequestedByUserId() { return requestedByUserId; }
    public void setRequestedByUserId(Long value) { requestedByUserId = value; }
    public Long getPermissionRevision() { return permissionRevision; }
    public void setPermissionRevision(Long value) { permissionRevision = value; }
    public Long getCredentialEpoch() { return credentialEpoch; }
    public void setCredentialEpoch(Long value) { credentialEpoch = value; }
    public LocalDateTime getAuthorizationExpiresAt() { return authorizationExpiresAt; }
    public void setAuthorizationExpiresAt(LocalDateTime value) { authorizationExpiresAt = value; }
    public Boolean getBusinessWide() { return businessWide; }
    public void setBusinessWide(Boolean value) { businessWide = value; }
}
