package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.time.LocalDateTime;

/** Opaque, short-lived credential for one authenticated user and one interview purpose. */
@TableName("interview_participant_ticket")
public class InterviewParticipantTicketEntity extends BaseEntity {
    private Long sessionId;
    private Long userId;
    private String purpose;
    private String ticketHash;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private Integer revision;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long value) { sessionId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String value) { purpose = value; }
    public String getTicketHash() { return ticketHash; }
    public void setTicketHash(String value) { ticketHash = value; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { expiresAt = value; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime value) { revokedAt = value; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer value) { revision = value; }
}
