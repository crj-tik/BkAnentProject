package com.bkanent.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 发放批次实体。
 */
@TableName("settlement_payout_batch")
public class SettlementPayoutBatchEntity extends BaseEntity {

    /**
     * 业务属性：batchNo。
     */
    private String batchNo;
    /**
     * 业务属性：statMonth。
     */
    private String statMonth;
    /**
     * 业务属性：batchStatus。
     */
    private String batchStatus;
    /**
     * 业务属性：totalRecords。
     */
    private Integer totalRecords;
    /**
     * 业务属性：totalAmount。
     */
    private BigDecimal totalAmount;
    /**
     * 业务属性：submitTime。
     */
    private String submitTime;
    /**
     * 业务属性：paidTime。
     */
    private String paidTime;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
    }

    public String getBatchStatus() {
        return batchStatus;
    }

    public void setBatchStatus(String batchStatus) {
        this.batchStatus = batchStatus;
    }

    public Integer getTotalRecords() {
        return totalRecords;
    }

    public void setTotalRecords(Integer totalRecords) {
        this.totalRecords = totalRecords;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public String getSubmitTime() {
        return submitTime;
    }

    public void setSubmitTime(String submitTime) {
        this.submitTime = submitTime;
    }

    public String getPaidTime() {
        return paidTime;
    }

    public void setPaidTime(String paidTime) {
        this.paidTime = paidTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
