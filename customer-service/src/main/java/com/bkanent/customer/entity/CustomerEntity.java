package com.bkanent.customer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 客户与业主档案实体。
 */
@TableName("customer_profile")
public class CustomerEntity extends BaseEntity {

    /**
     * 业务属性：profileType。
     */
    private String profileType;
    /**
     * 业务属性：name。
     */
    private String name;
    /**
     * 业务属性：mobile。
     */
    private String mobile;
    /**
     * 业务属性：wechatNo。
     */
    private String wechatNo;
    /**
     * 业务属性：gender。
     */
    private String gender;
    /**
     * 业务属性：intention。
     */
    private String intention;
    /**
     * 业务属性：preferredArea。
     */
    private String preferredArea;
    /**
     * 业务属性：preferredLayout。
     */
    private String preferredLayout;
    /**
     * 业务属性：budgetMin。
     */
    private BigDecimal budgetMin;
    /**
     * 业务属性：budgetMax。
     */
    private BigDecimal budgetMax;
    /**
     * 业务属性：preferredAreaMin。
     */
    private BigDecimal preferredAreaMin;
    /**
     * 业务属性：preferredAreaMax。
     */
    private BigDecimal preferredAreaMax;
    /**
     * 业务属性：brokerId。
     */
    private Long brokerId;
    /**
     * 业务属性：sourceChannel。
     */
    private String sourceChannel;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public String getProfileType() {
        return profileType;
    }

    public void setProfileType(String profileType) {
        this.profileType = profileType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMobile() {
        return mobile;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile;
    }

    public String getWechatNo() {
        return wechatNo;
    }

    public void setWechatNo(String wechatNo) {
        this.wechatNo = wechatNo;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getIntention() {
        return intention;
    }

    public void setIntention(String intention) {
        this.intention = intention;
    }

    public String getPreferredArea() {
        return preferredArea;
    }

    public void setPreferredArea(String preferredArea) {
        this.preferredArea = preferredArea;
    }

    public String getPreferredLayout() {
        return preferredLayout;
    }

    public void setPreferredLayout(String preferredLayout) {
        this.preferredLayout = preferredLayout;
    }

    public BigDecimal getBudgetMin() {
        return budgetMin;
    }

    public void setBudgetMin(BigDecimal budgetMin) {
        this.budgetMin = budgetMin;
    }

    public BigDecimal getBudgetMax() {
        return budgetMax;
    }

    public void setBudgetMax(BigDecimal budgetMax) {
        this.budgetMax = budgetMax;
    }

    public BigDecimal getPreferredAreaMin() {
        return preferredAreaMin;
    }

    public void setPreferredAreaMin(BigDecimal preferredAreaMin) {
        this.preferredAreaMin = preferredAreaMin;
    }

    public BigDecimal getPreferredAreaMax() {
        return preferredAreaMax;
    }

    public void setPreferredAreaMax(BigDecimal preferredAreaMax) {
        this.preferredAreaMax = preferredAreaMax;
    }

    public Long getBrokerId() {
        return brokerId;
    }

    public void setBrokerId(Long brokerId) {
        this.brokerId = brokerId;
    }

    public String getSourceChannel() {
        return sourceChannel;
    }

    public void setSourceChannel(String sourceChannel) {
        this.sourceChannel = sourceChannel;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
