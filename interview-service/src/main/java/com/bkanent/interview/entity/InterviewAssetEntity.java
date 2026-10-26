package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 逐字稿资产：L0-L3 确定性评级、冻结快照哈希。
 */
@TableName("interview_asset")
public class InterviewAssetEntity extends BaseEntity {

    /** AI 归集来源会话；人工上传为空 */
    private Long sessionId;
    private Long caseId;
    /** AUTO|UPLOAD */
    private String source;
    private String title;
    /** 脱敏域 Markdown 正文 */
    private String content;
    /** 冻结快照哈希（防覆盖） */
    private String snapshotHash;
    /** L0|L1|L2|L3 */
    private String archiveGrade;
    /** 六项检查逐项依据 JSON */
    private String gradeBasis;
    private Integer turnCount;
    /** 13 个业务元数据字段 JSON（对齐 structuredContext） */
    private String metadataJson;
    private Integer wordCount;
    /** Original upload kept only inside the authenticated business domain. */
    private String rawContent;
    /** Hash of the sanitized content projection consumed by reports and public reads. */
    private String contentHash;
    private Integer contentRevision;
    /** Zero means legacy/unknown; public projection requires a reviewed sanitizer revision. */
    private Integer sanitizationRevision;
    /** PRIVATE until an explicit publish operation. */
    private String visibility;
    private Integer publicationRevision;
    private Long approvedByUserId;
    private LocalDateTime publishedAt;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSnapshotHash() { return snapshotHash; }
    public void setSnapshotHash(String snapshotHash) { this.snapshotHash = snapshotHash; }
    public String getArchiveGrade() { return archiveGrade; }
    public void setArchiveGrade(String archiveGrade) { this.archiveGrade = archiveGrade; }
    public String getGradeBasis() { return gradeBasis; }
    public void setGradeBasis(String gradeBasis) { this.gradeBasis = gradeBasis; }
    public Integer getTurnCount() { return turnCount; }
    public void setTurnCount(Integer turnCount) { this.turnCount = turnCount; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public Integer getWordCount() { return wordCount; }
    public void setWordCount(Integer wordCount) { this.wordCount = wordCount; }
    public String getRawContent() { return rawContent; }
    public void setRawContent(String value) { rawContent = value; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String value) { contentHash = value; }
    public Integer getContentRevision() { return contentRevision; }
    public void setContentRevision(Integer value) { contentRevision = value; }
    public Integer getSanitizationRevision() { return sanitizationRevision; }
    public void setSanitizationRevision(Integer value) { sanitizationRevision = value; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String value) { visibility = value; }
    public Integer getPublicationRevision() { return publicationRevision; }
    public void setPublicationRevision(Integer value) { publicationRevision = value; }
    public Long getApprovedByUserId() { return approvedByUserId; }
    public void setApprovedByUserId(Long value) { approvedByUserId = value; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime value) { publishedAt = value; }
}
