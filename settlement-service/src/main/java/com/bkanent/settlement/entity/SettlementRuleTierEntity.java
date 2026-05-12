package com.bkanent.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 阶梯佣金规则实体。
 */
@TableName("settlement_rule_tier")
public class SettlementRuleTierEntity extends BaseEntity {

    /**
     * 业务属性：ruleId。
     */
    private Long ruleId;
    /**
     * 业务属性：tierLevel。
     */
    private Integer tierLevel;
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
     * 业务属性：remark。
     */
    private String remark;

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public Integer getTierLevel() {
        return tierLevel;
    }

    public void setTierLevel(Integer tierLevel) {
        this.tierLevel = tierLevel;
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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
