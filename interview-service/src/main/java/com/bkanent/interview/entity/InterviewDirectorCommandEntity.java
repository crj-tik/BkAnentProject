package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 导演指令：人工干预留痕，运行面在下一话轮前消费。
 */
@TableName("interview_director_command")
public class InterviewDirectorCommandEntity extends BaseEntity {

    private Long sessionId;
    /** WRAP_UP|NEXT_QUESTION|PINNED_QUESTION|END_SESSION */
    private String command;
    /** PINNED_QUESTION 的逐字播出文本 */
    private String pinnedQuestion;
    /** PENDING|CONSUMED */
    private String status;
    /** A2A|DIRECT_REST */
    private String source;
    /** Signed actor facts for versioned collaboration commands; null only in legacy mode. */
    private Long requestedByUserId;
    private Long permissionRevision;
    private Long credentialEpoch;
    private LocalDateTime authorizationExpiresAt;
    private Boolean businessWide;
    private String authorizationOperation;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getPinnedQuestion() { return pinnedQuestion; }
    public void setPinnedQuestion(String pinnedQuestion) { this.pinnedQuestion = pinnedQuestion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
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
    public String getAuthorizationOperation() { return authorizationOperation; }
    public void setAuthorizationOperation(String value) { authorizationOperation = value; }
}
