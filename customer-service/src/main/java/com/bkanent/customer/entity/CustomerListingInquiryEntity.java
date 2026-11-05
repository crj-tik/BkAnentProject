package com.bkanent.customer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 买方房源咨询实体，映射 bk_customer.customer_listing_inquiry。
 * 业务状态（OPEN/FOLLOWING/CLOSED）与验证状态（PENDING/CONFIRMED/NEEDS_RECHECK/REJECTED）相互独立。
 */
@TableName("customer_listing_inquiry")
public class CustomerListingInquiryEntity extends BaseEntity {

    /**
     * 业务属性：customerId（买方档案 ID，跨库逻辑关联）。
     */
    private Long customerId;
    /**
     * 业务属性：listingId（内部房源 ID，跨库逻辑关联）。
     */
    private Long listingId;
    /**
     * 业务属性：brokerId（可信负责经纪人）。
     */
    private Long brokerId;
    /**
     * 业务属性：inquiryChannel（咨询渠道）。
     */
    private String inquiryChannel;
    /**
     * 业务属性：inquiryContent（咨询内容）。
     */
    private String inquiryContent;
    /**
     * 业务属性：status（业务状态 OPEN/FOLLOWING/CLOSED）。
     */
    private String status;
    /**
     * 业务属性：validationState（验证状态 PENDING/CONFIRMED/NEEDS_RECHECK/REJECTED）。
     */
    private String validationState;
    /**
     * 业务属性：checkedListingVersion（验证时的房源版本）。
     */
    private String checkedListingVersion;
    /**
     * 业务属性：checkedAt（验证时间）。
     */
    private LocalDateTime checkedAt;

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

    public Long getBrokerId() {
        return brokerId;
    }

    public void setBrokerId(Long brokerId) {
        this.brokerId = brokerId;
    }

    public String getInquiryChannel() {
        return inquiryChannel;
    }

    public void setInquiryChannel(String inquiryChannel) {
        this.inquiryChannel = inquiryChannel;
    }

    public String getInquiryContent() {
        return inquiryContent;
    }

    public void setInquiryContent(String inquiryContent) {
        this.inquiryContent = inquiryContent;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getValidationState() {
        return validationState;
    }

    public void setValidationState(String validationState) {
        this.validationState = validationState;
    }

    public String getCheckedListingVersion() {
        return checkedListingVersion;
    }

    public void setCheckedListingVersion(String checkedListingVersion) {
        this.checkedListingVersion = checkedListingVersion;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public void setCheckedAt(LocalDateTime checkedAt) {
        this.checkedAt = checkedAt;
    }
}
