package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 访谈话轮：决策动作、拦截原因、幂等键。
 */
@TableName("interview_turn")
public class InterviewTurnEntity extends BaseEntity {

    private Long sessionId;
    private Integer turnSeq;
    /** INTERVIEWER|RESPONDENT|DIRECTOR */
    private String role;
    private String content;
    /** 脱敏后正文（落库存脱敏域文本） */
    private String sanitizedContent;
    /** CLOSE|ACK_AND_SWITCH|ANGLE|ADVANCE|OPEN_DRILL */
    private String probeMove;
    private Long questionId;
    /** 出模闸拦截原因 */
    private String blockedReason;
    private String idempotencyKey;
    private Integer persistRetries;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Integer getTurnSeq() { return turnSeq; }
    public void setTurnSeq(Integer turnSeq) { this.turnSeq = turnSeq; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSanitizedContent() { return sanitizedContent; }
    public void setSanitizedContent(String sanitizedContent) { this.sanitizedContent = sanitizedContent; }
    public String getProbeMove() { return probeMove; }
    public void setProbeMove(String probeMove) { this.probeMove = probeMove; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getBlockedReason() { return blockedReason; }
    public void setBlockedReason(String blockedReason) { this.blockedReason = blockedReason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Integer getPersistRetries() { return persistRetries; }
    public void setPersistRetries(Integer persistRetries) { this.persistRetries = persistRetries; }
}
