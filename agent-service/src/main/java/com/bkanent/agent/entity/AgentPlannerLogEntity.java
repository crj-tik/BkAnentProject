package com.bkanent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * Planner 会话日志实体。
 */
@TableName("agent_planner_log")
public class AgentPlannerLogEntity extends BaseEntity {

    /**
     * 业务属性：sessionNo。
     */
    private String sessionNo;
    /**
     * 业务属性：executionMode。
     */
    private String executionMode;
    /**
     * 业务属性：userMessage。
     */
    private String userMessage;
    /**
     * 业务属性：finalAnswer。
     */
    private String finalAnswer;
    /**
     * 业务属性：planSummary。
     */
    private String planSummary;
    /**
     * 业务属性：finalPlanJson。
     */
    private String finalPlanJson;
    /**
     * 业务属性：toolContext。
     */
    private String toolContext;
    /**
     * 业务属性：replanCount。
     */
    private Integer replanCount;
    /**
     * 业务属性：completed。
     */
    private Integer completed;

    public String getSessionNo() {
        return sessionNo;
    }

    public void setSessionNo(String sessionNo) {
        this.sessionNo = sessionNo;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public String getPlanSummary() {
        return planSummary;
    }

    public void setPlanSummary(String planSummary) {
        this.planSummary = planSummary;
    }

    public String getFinalPlanJson() {
        return finalPlanJson;
    }

    public void setFinalPlanJson(String finalPlanJson) {
        this.finalPlanJson = finalPlanJson;
    }

    public String getToolContext() {
        return toolContext;
    }

    public void setToolContext(String toolContext) {
        this.toolContext = toolContext;
    }

    public Integer getReplanCount() {
        return replanCount;
    }

    public void setReplanCount(Integer replanCount) {
        this.replanCount = replanCount;
    }

    public Integer getCompleted() {
        return completed;
    }

    public void setCompleted(Integer completed) {
        this.completed = completed;
    }
}
