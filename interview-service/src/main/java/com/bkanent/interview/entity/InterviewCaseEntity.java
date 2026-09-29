package com.bkanent.interview.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 访谈案例主实体：五大场景、案例状态机、禁成功预设。
 */
@TableName("interview_case")
public class InterviewCaseEntity extends BaseEntity {

    /** 场景：STORE_MANAGER|AGENT_DEAL|SECOND_HAND_PARTY|NEW_HOUSE_FIELD|COMMUNITY_EXPERT */
    private String scene;
    /** 案例状态：won|active|lost|churned */
    private String caseStatus;
    /** 受访人角色（按场景枚举） */
    private String respondentRole;
    /** 访谈目标一句话 */
    private String objective;
    private String divisionName;
    private String regionName;
    private String businessDistrict;
    private String projectName;
    private String storeName;
    private String listingName;
    private String respondentName;
    /** 禁成功预设：未成交/流失案例自动开启 */
    private Integer noSuccessPreset;
    /** 必采清单 JSON（禁成功预设时启用） */
    private String mustCollectItems;
    /** 创建人工号（双入口对齐字段） */
    private String creatorWorkNo;

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }
    public String getCaseStatus() { return caseStatus; }
    public void setCaseStatus(String caseStatus) { this.caseStatus = caseStatus; }
    public String getRespondentRole() { return respondentRole; }
    public void setRespondentRole(String respondentRole) { this.respondentRole = respondentRole; }
    public String getObjective() { return objective; }
    public void setObjective(String objective) { this.objective = objective; }
    public String getDivisionName() { return divisionName; }
    public void setDivisionName(String divisionName) { this.divisionName = divisionName; }
    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }
    public String getBusinessDistrict() { return businessDistrict; }
    public void setBusinessDistrict(String businessDistrict) { this.businessDistrict = businessDistrict; }
    public String getProjectName() { return projectName; }
    public void setProjectName(String projectName) { this.projectName = projectName; }
    public String getStoreName() { return storeName; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public String getListingName() { return listingName; }
    public void setListingName(String listingName) { this.listingName = listingName; }
    public String getRespondentName() { return respondentName; }
    public void setRespondentName(String respondentName) { this.respondentName = respondentName; }
    public Integer getNoSuccessPreset() { return noSuccessPreset; }
    public void setNoSuccessPreset(Integer noSuccessPreset) { this.noSuccessPreset = noSuccessPreset; }
    public String getMustCollectItems() { return mustCollectItems; }
    public void setMustCollectItems(String mustCollectItems) { this.mustCollectItems = mustCollectItems; }
    public String getCreatorWorkNo() { return creatorWorkNo; }
    public void setCreatorWorkNo(String creatorWorkNo) { this.creatorWorkNo = creatorWorkNo; }
}
