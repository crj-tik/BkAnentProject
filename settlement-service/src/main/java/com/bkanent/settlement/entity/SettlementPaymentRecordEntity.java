package com.bkanent.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 打款流水实体。
 */
@TableName("settlement_payment_record")
public class SettlementPaymentRecordEntity extends BaseEntity {

    /**
     * 业务属性：batchId。
     */
    private Long batchId;
    /**
     * 业务属性：settlementId。
     */
    private Long settlementId;
    /**
     * 业务属性：payeeEmployeeId。
     */
    private Long payeeEmployeeId;
    /**
     * 业务属性：payeeName。
     */
    private String payeeName;
    /**
     * 业务属性：paymentAmount。
     */
    private BigDecimal paymentAmount;
    /**
     * 业务属性：paymentStatus。
     */
    private String paymentStatus;
    /**
     * 业务属性：paymentTime。
     */
    private String paymentTime;
    /**
     * 业务属性：bankSerialNo。
     */
    private String bankSerialNo;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getSettlementId() {
        return settlementId;
    }

    public void setSettlementId(Long settlementId) {
        this.settlementId = settlementId;
    }

    public Long getPayeeEmployeeId() {
        return payeeEmployeeId;
    }

    public void setPayeeEmployeeId(Long payeeEmployeeId) {
        this.payeeEmployeeId = payeeEmployeeId;
    }

    public String getPayeeName() {
        return payeeName;
    }

    public void setPayeeName(String payeeName) {
        this.payeeName = payeeName;
    }

    public BigDecimal getPaymentAmount() {
        return paymentAmount;
    }

    public void setPaymentAmount(BigDecimal paymentAmount) {
        this.paymentAmount = paymentAmount;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getPaymentTime() {
        return paymentTime;
    }

    public void setPaymentTime(String paymentTime) {
        this.paymentTime = paymentTime;
    }

    public String getBankSerialNo() {
        return bankSerialNo;
    }

    public void setBankSerialNo(String bankSerialNo) {
        this.bankSerialNo = bankSerialNo;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
