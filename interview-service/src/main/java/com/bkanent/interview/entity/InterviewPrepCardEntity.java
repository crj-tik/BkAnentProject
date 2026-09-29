package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 访前基础卡：动态字段、信息完整度、出题策略、背景材料。
 */
@TableName("interview_prep_card")
public class InterviewPrepCardEntity extends BaseEntity {

    private Long caseId;
    /** 按场景的 6 个动态字段 JSON */
    private String dynamicFields;
    /** LOW|MEDIUM|HIGH */
    private String completeness;
    /** EXPLORE|DIRECTED_SUPPLEMENT|TIMELINE_FILL */
    private String questionStrategy;
    private String backgroundMaterial;
    /** 知识库拉取注入的片段 JSON */
    private String knowledgeSnippets;

    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public String getDynamicFields() { return dynamicFields; }
    public void setDynamicFields(String dynamicFields) { this.dynamicFields = dynamicFields; }
    public String getCompleteness() { return completeness; }
    public void setCompleteness(String completeness) { this.completeness = completeness; }
    public String getQuestionStrategy() { return questionStrategy; }
    public void setQuestionStrategy(String questionStrategy) { this.questionStrategy = questionStrategy; }
    public String getBackgroundMaterial() { return backgroundMaterial; }
    public void setBackgroundMaterial(String backgroundMaterial) { this.backgroundMaterial = backgroundMaterial; }
    public String getKnowledgeSnippets() { return knowledgeSnippets; }
    public void setKnowledgeSnippets(String knowledgeSnippets) { this.knowledgeSnippets = knowledgeSnippets; }
}
