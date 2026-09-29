package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 访谈报告：案例卡结构化结论、证据核验、缺失三分类、三轴可复制性。
 */
@TableName("interview_report")
public class InterviewReportEntity extends BaseEntity {

    private Long caseId;
    private Long sessionId;
    private Long assetId;
    /** P1 仅 CASE_CARD */
    private String reportType;
    private String reportJson;
    private String evidenceVerification;
    private String missingItems;
    /** 质量分（缺失 1 项封顶 79） */
    private Integer score;
    /** L0-L3 可复制性（成功案例不得直接判 validated） */
    private String replicabilityLevel;
    private String inputSnapshotHash;

    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }
    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
    public String getEvidenceVerification() { return evidenceVerification; }
    public void setEvidenceVerification(String evidenceVerification) { this.evidenceVerification = evidenceVerification; }
    public String getMissingItems() { return missingItems; }
    public void setMissingItems(String missingItems) { this.missingItems = missingItems; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getReplicabilityLevel() { return replicabilityLevel; }
    public void setReplicabilityLevel(String replicabilityLevel) { this.replicabilityLevel = replicabilityLevel; }
    public String getInputSnapshotHash() { return inputSnapshotHash; }
    public void setInputSnapshotHash(String inputSnapshotHash) { this.inputSnapshotHash = inputSnapshotHash; }
}
