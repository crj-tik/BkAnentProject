package com.bkanent.customer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.time.LocalDate;

/**
 * 业主委托记录实体。
 */
@TableName("owner_entrust_record")
public class OwnerEntrustRecordEntity extends BaseEntity {

    /**
     * 业务属性：customerId。
     */
    private Long customerId;
    /**
     * 业务属性：listingId。
     */
    private Long listingId;
    /**
     * 业务属性：contractNo。
     */
    private String contractNo;
    /**
     * 业务属性：entrustStartDate。
     */
    private LocalDate entrustStartDate;
    /**
     * 业务属性：entrustEndDate。
     */
    private LocalDate entrustEndDate;
    /**
     * 业务属性：reminderDays。
     */
    private Integer reminderDays;
    /**
     * 业务属性：status。
     */
    private String status;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public String getContractNo() {
        return contractNo;
    }

    public void setContractNo(String contractNo) {
        this.contractNo = contractNo;
    }

    public LocalDate getEntrustStartDate() {
        return entrustStartDate;
    }

    public void setEntrustStartDate(LocalDate entrustStartDate) {
        this.entrustStartDate = entrustStartDate;
    }

    public LocalDate getEntrustEndDate() {
        return entrustEndDate;
    }

    public void setEntrustEndDate(LocalDate entrustEndDate) {
        this.entrustEndDate = entrustEndDate;
    }

    public Integer getReminderDays() {
        return reminderDays;
    }

    public void setReminderDays(Integer reminderDays) {
        this.reminderDays = reminderDays;
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
