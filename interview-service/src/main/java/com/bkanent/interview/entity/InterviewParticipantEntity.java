package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.time.LocalDateTime;

/** Per-session user relationship, never an Auth role. */
@TableName("interview_participant")
public class InterviewParticipantEntity extends BaseEntity {
    private Long sessionId;
    private Long userId;
    private String relation;
    private String state;
    private Long invitedByUserId;
    private LocalDateTime confirmedAt;
    private Integer revision;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { sessionId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getRelation() { return relation; }
    public void setRelation(String value) { relation = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public Long getInvitedByUserId() { return invitedByUserId; }
    public void setInvitedByUserId(Long value) { invitedByUserId = value; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime value) { confirmedAt = value; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer value) { revision = value; }
}
