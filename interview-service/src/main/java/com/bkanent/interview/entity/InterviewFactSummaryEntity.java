package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 受访者已讲事实累积摘要：记忆三段装配源。
 */
@TableName("interview_fact_summary")
public class InterviewFactSummaryEntity extends BaseEntity {

    private Long sessionId;
    private String summaryText;
    /** 摘要覆盖到第几轮 */
    private Integer turnCutoff;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public Integer getTurnCutoff() { return turnCutoff; }
    public void setTurnCutoff(Integer turnCutoff) { this.turnCutoff = turnCutoff; }
}
