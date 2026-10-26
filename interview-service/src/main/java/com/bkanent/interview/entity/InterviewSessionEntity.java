package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 访谈会话：状态机、乐观锁、收尾锁、行业大脑预读缓存。
 */
@TableName("interview_session")
public class InterviewSessionEntity extends BaseEntity {

    private Long caseId;
    /** AI_LEAD|ASSIST|FORM */
    private String mode;
    /** DRAFT|QUESTIONS_CONFIRMED|IN_PROGRESS|CLOSING_LOCKED|COLLECT_PENDING|ARCHIVED */
    private String status;
    /** 乐观锁 */
    private Integer version;
    private Integer referenceMinutes;
    /** XIAOZHI|XIAOFENG|COMMUNITY_26Q（P1 仅 XIAOZHI） */
    private String engineType;
    /** 收尾持久锁：首次收尾后强制极短对等道别 */
    private Integer closingLocked;
    /** 风险主题跨轮计数 JSON（同类题全场≤2） */
    private String riskTopicCounter;
    /** 行业大脑场景包开台预读缓存 JSON（访中零跨服务） */
    private String industryBrainCache;
    /** A2A|FORM 双入口 */
    private String entrySource;
    private String creatorWorkNo;
    /** Auth actor id; null on legacy rows whose owner has not been verified. */
    private Long createdByUserId;
    private LocalDateTime startedAt;
    private LocalDateTime closedAt;

    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Integer getReferenceMinutes() { return referenceMinutes; }
    public void setReferenceMinutes(Integer referenceMinutes) { this.referenceMinutes = referenceMinutes; }
    public String getEngineType() { return engineType; }
    public void setEngineType(String engineType) { this.engineType = engineType; }
    public Integer getClosingLocked() { return closingLocked; }
    public void setClosingLocked(Integer closingLocked) { this.closingLocked = closingLocked; }
    public String getRiskTopicCounter() { return riskTopicCounter; }
    public void setRiskTopicCounter(String riskTopicCounter) { this.riskTopicCounter = riskTopicCounter; }
    public String getIndustryBrainCache() { return industryBrainCache; }
    public void setIndustryBrainCache(String industryBrainCache) { this.industryBrainCache = industryBrainCache; }
    public String getEntrySource() { return entrySource; }
    public void setEntrySource(String entrySource) { this.entrySource = entrySource; }
    public String getCreatorWorkNo() { return creatorWorkNo; }
    public void setCreatorWorkNo(String creatorWorkNo) { this.creatorWorkNo = creatorWorkNo; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
}
