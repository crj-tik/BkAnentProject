package com.bkanent.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 佣金规则实体。
 */
@TableName("settlement_rule")
public class SettlementRuleEntity extends BaseEntity {

    /**
     * 业务属性：ruleCode。
     */
    private String ruleCode;
    /**
     * 业务属性：ruleName。
     */
    private String ruleName;
    /**
     * 业务属性：contractType。
     */
    private String contractType;
    /**
     * 业务属性：minDealAmount。
     */
    private BigDecimal minDealAmount;
    /**
     * 业务属性：maxDealAmount。
     */
    private BigDecimal maxDealAmount;
    /**
     * 业务属性：commissionRate。
     */
    private BigDecimal commissionRate;
    /**
     * 业务属性：storeSplitRatio。
     */
    private BigDecimal storeSplitRatio;
    /**
     * 业务属性：teamSplitRatio。
     */
    private BigDecimal teamSplitRatio;
    /**
     * 业务属性：status。
     */
    private String status;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getContractType() {
        return contractType;
    }

    public void setContractType(String contractType) {
        this.contractType = contractType;
    }

    public BigDecimal getMinDealAmount() {
        return minDealAmount;
    }

    public void setMinDealAmount(BigDecimal minDealAmount) {
        this.minDealAmount = minDealAmount;
    }

    public BigDecimal getMaxDealAmount() {
        return maxDealAmount;
    }

    public void setMaxDealAmount(BigDecimal maxDealAmount) {
        this.maxDealAmount = maxDealAmount;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
    }

    public BigDecimal getStoreSplitRatio() {
        return storeSplitRatio;
    }

    public void setStoreSplitRatio(BigDecimal storeSplitRatio) {
        this.storeSplitRatio = storeSplitRatio;
    }

    public BigDecimal getTeamSplitRatio() {
        return teamSplitRatio;
    }

    public void setTeamSplitRatio(BigDecimal teamSplitRatio) {
        this.teamSplitRatio = teamSplitRatio;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
