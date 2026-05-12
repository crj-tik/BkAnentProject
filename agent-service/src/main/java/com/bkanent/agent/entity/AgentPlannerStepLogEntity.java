package com.bkanent.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * Planner 步骤日志实体。
 */
@TableName("agent_planner_step_log")
public class AgentPlannerStepLogEntity extends BaseEntity {

    /**
     * 业务属性：plannerLogId。
     */
    private Long plannerLogId;
    /**
     * 业务属性：sessionNo。
     */
    private String sessionNo;
    /**
     * 业务属性：stepNo。
     */
    private Integer stepNo;
    /**
     * 业务属性：action。
     */
    private String action;
    /**
     * 业务属性：success。
     */
    private Integer success;
    /**
     * 业务属性：skipped。
     */
    private Integer skipped;
    /**
     * 业务属性：requestContent。
     */
    private String requestContent;
    /**
     * 业务属性：resolvedInput。
     */
    private String resolvedInput;
    /**
     * 业务属性：outputKey。
     */
    private String outputKey;
    /**
     * 业务属性：outputContent。
     */
    private String outputContent;
    /**
     * 业务属性：outputPayloadJson。
     */
    private String outputPayloadJson;
    /**
     * 业务属性：errorMessage。
     */
    private String errorMessage;

    public Long getPlannerLogId() {
        return plannerLogId;
    }

    public void setPlannerLogId(Long plannerLogId) {
        this.plannerLogId = plannerLogId;
    }

    public String getSessionNo() {
        return sessionNo;
    }

    public void setSessionNo(String sessionNo) {
        this.sessionNo = sessionNo;
    }

    public Integer getStepNo() {
        return stepNo;
    }

    public void setStepNo(Integer stepNo) {
        this.stepNo = stepNo;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getSuccess() {
        return success;
    }

    public void setSuccess(Integer success) {
        this.success = success;
    }

    public Integer getSkipped() {
        return skipped;
    }

    public void setSkipped(Integer skipped) {
        this.skipped = skipped;
    }

    public String getRequestContent() {
        return requestContent;
    }

    public void setRequestContent(String requestContent) {
        this.requestContent = requestContent;
    }

    public String getResolvedInput() {
        return resolvedInput;
    }

    public void setResolvedInput(String resolvedInput) {
        this.resolvedInput = resolvedInput;
    }

    public String getOutputKey() {
        return outputKey;
    }

    public void setOutputKey(String outputKey) {
        this.outputKey = outputKey;
    }

    public String getOutputContent() {
        return outputContent;
    }

    public void setOutputContent(String outputContent) {
        this.outputContent = outputContent;
    }

    public String getOutputPayloadJson() {
        return outputPayloadJson;
    }

    public void setOutputPayloadJson(String outputPayloadJson) {
        this.outputPayloadJson = outputPayloadJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
