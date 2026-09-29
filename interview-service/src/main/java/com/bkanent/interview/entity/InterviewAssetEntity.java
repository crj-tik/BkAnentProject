package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

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
}
