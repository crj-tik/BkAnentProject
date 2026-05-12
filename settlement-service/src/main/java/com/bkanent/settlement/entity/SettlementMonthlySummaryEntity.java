package com.bkanent.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 月度提成汇总实体。
 */
@TableName("settlement_monthly_summary")
public class SettlementMonthlySummaryEntity extends BaseEntity {

    /**
     * 业务属性：summaryScope。
     */
    private String summaryScope;
    /**
     * 业务属性：employeeId。
     */
    private Long employeeId;
    /**
     * 业务属性：employeeName。
     */
    private String employeeName;
    /**
     * 业务属性：teamName。
     */
    private String teamName;
    /**
     * 业务属性：statMonth。
     */
    private String statMonth;
    /**
     * 业务属性：dealCount。
     */
    private Integer dealCount;
    /**
     * 业务属性：totalDealAmount。
     */
    private BigDecimal totalDealAmount;
    /**
     * 业务属性：totalCommissionAmount。
     */
    private BigDecimal totalCommissionAmount;
    /**
     * 业务属性：payoutStatus。
     */
    private String payoutStatus;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public String getSummaryScope() {
        return summaryScope;
    }

    public void setSummaryScope(String summaryScope) {
        this.summaryScope = summaryScope;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(Long employeeId) {
        this.employeeId = employeeId;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public void setEmployeeName(String employeeName) {
        this.employeeName = employeeName;
    }

    public String getTeamName() {
        return teamName;
    }

    public void setTeamName(String teamName) {
        this.teamName = teamName;
    }

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
    }

    public Integer getDealCount() {
        return dealCount;
    }

    public void setDealCount(Integer dealCount) {
        this.dealCount = dealCount;
    }

    public BigDecimal getTotalDealAmount() {
        return totalDealAmount;
    }

    public void setTotalDealAmount(BigDecimal totalDealAmount) {
        this.totalDealAmount = totalDealAmount;
    }

    public BigDecimal getTotalCommissionAmount() {
        return totalCommissionAmount;
    }

    public void setTotalCommissionAmount(BigDecimal totalCommissionAmount) {
        this.totalCommissionAmount = totalCommissionAmount;
    }

    public String getPayoutStatus() {
        return payoutStatus;
    }

    public void setPayoutStatus(String payoutStatus) {
        this.payoutStatus = payoutStatus;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
