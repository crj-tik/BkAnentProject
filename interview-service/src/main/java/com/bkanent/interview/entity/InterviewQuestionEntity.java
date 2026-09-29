package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 访谈题目：题目确认制——只有确认题进入访谈。
 */
@TableName("interview_question")
public class InterviewQuestionEntity extends BaseEntity {

    private Long caseId;
    /** T1-T8 提纲路由 */
    private String outlineRoute;
    private Integer seqNo;
    private String content;
    private String focusLabel;
    private String riskHint;
    /** 题目确认制 */
    private Integer confirmed;
    /** PENDING|ANSWERED|SKIPPED */
    private String answerStatus;
    private Integer probeRounds;
    /** 深度上限（核心题 4-5，一般题 2-3） */
    private Integer depthLimit;
    /** 核心题（前两题） */
    private Integer isCore;

    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public String getOutlineRoute() { return outlineRoute; }
    public void setOutlineRoute(String outlineRoute) { this.outlineRoute = outlineRoute; }
    public Integer getSeqNo() { return seqNo; }
    public void setSeqNo(Integer seqNo) { this.seqNo = seqNo; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getFocusLabel() { return focusLabel; }
    public void setFocusLabel(String focusLabel) { this.focusLabel = focusLabel; }
    public String getRiskHint() { return riskHint; }
    public void setRiskHint(String riskHint) { this.riskHint = riskHint; }
    public Integer getConfirmed() { return confirmed; }
    public void setConfirmed(Integer confirmed) { this.confirmed = confirmed; }
    public String getAnswerStatus() { return answerStatus; }
    public void setAnswerStatus(String answerStatus) { this.answerStatus = answerStatus; }
    public Integer getProbeRounds() { return probeRounds; }
    public void setProbeRounds(Integer probeRounds) { this.probeRounds = probeRounds; }
    public Integer getDepthLimit() { return depthLimit; }
    public void setDepthLimit(Integer depthLimit) { this.depthLimit = depthLimit; }
    public Integer getIsCore() { return isCore; }
    public void setIsCore(Integer isCore) { this.isCore = isCore; }
}
