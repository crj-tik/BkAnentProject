package com.bkanent.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

@TableName("settlement_record")
public class SettlementRecordEntity extends BaseEntity {

    private Long employeeId;
    private String statMonth;
    private BigDecimal commissionAmount;
    private String payoutStatus;

    public Long getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Long employeeId) {
        this.employeeId = employeeId;
    }

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
    }

    public BigDecimal getCommissionAmount() {
        return commissionAmount;
    }

    public void setCommissionAmount(BigDecimal commissionAmount) {
        this.commissionAmount = commissionAmount;
    }

    public String getPayoutStatus() {
        return payoutStatus;
    }

    public void setPayoutStatus(String payoutStatus) {
        this.payoutStatus = payoutStatus;
    }
}
