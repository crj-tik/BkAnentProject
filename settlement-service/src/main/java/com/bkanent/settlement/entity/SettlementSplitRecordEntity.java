package com.bkanent.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 分佣明细实体。
 */
@TableName("settlement_split_record")
public class SettlementSplitRecordEntity extends BaseEntity {

    /**
     * 业务属性：settlementId。
     */
    private Long settlementId;
    /**
     * 业务属性：splitScope。
     */
    private String splitScope;
    /**
     * 业务属性：splitTargetName。
     */
    private String splitTargetName;
    /**
     * 业务属性：splitRatio。
     */
    private BigDecimal splitRatio;
    /**
     * 业务属性：splitAmount。
     */
    private BigDecimal splitAmount;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public Long getSettlementId() {
        return settlementId;
    }

    public void setSettlementId(Long settlementId) {
        this.settlementId = settlementId;
    }

    public String getSplitScope() {
        return splitScope;
    }

    public void setSplitScope(String splitScope) {
        this.splitScope = splitScope;
    }

    public String getSplitTargetName() {
        return splitTargetName;
    }

    public void setSplitTargetName(String splitTargetName) {
        this.splitTargetName = splitTargetName;
    }

    public BigDecimal getSplitRatio() {
        return splitRatio;
    }

    public void setSplitRatio(BigDecimal splitRatio) {
        this.splitRatio = splitRatio;
    }

    public BigDecimal getSplitAmount() {
        return splitAmount;
    }

    public void setSplitAmount(BigDecimal splitAmount) {
        this.splitAmount = splitAmount;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
