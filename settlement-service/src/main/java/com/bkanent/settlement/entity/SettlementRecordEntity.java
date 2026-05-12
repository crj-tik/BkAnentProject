package com.bkanent.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 结算主记录实体。
 */
@TableName("settlement_record")
public class SettlementRecordEntity extends BaseEntity {

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
     * 业务属性：storeName。
     */
    private String storeName;
    /**
     * 业务属性：contractId。
     */
    private Long contractId;
    /**
     * 业务属性：listingId。
     */
    private Long listingId;
    /**
     * 业务属性：statMonth。
     */
    private String statMonth;
    /**
     * 业务属性：dealAmount。
     */
    private BigDecimal dealAmount;
    /**
     * 业务属性：commissionRate。
     */
    private BigDecimal commissionRate;
    /**
     * 业务属性：commissionAmount。
     */
    private BigDecimal commissionAmount;
    /**
     * 业务属性：payoutStatus。
     */
    private String payoutStatus;
    /**
     * 业务属性：payoutTime。
     */
    private String payoutTime;
    /**
     * 业务属性：ruleCode。
     */
    private String ruleCode;
    /**
     * 业务属性：remark。
     */
    private String remark;

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

    public String getStoreName() {
        return storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public Long getContractId() {
        return contractId;
    }

    public void setContractId(Long contractId) {
        this.contractId = contractId;
    }

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
    }

    public BigDecimal getDealAmount() {
        return dealAmount;
    }

    public void setDealAmount(BigDecimal dealAmount) {
        this.dealAmount = dealAmount;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
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

    public String getPayoutTime() {
        return payoutTime;
    }

    public void setPayoutTime(String payoutTime) {
        this.payoutTime = payoutTime;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
