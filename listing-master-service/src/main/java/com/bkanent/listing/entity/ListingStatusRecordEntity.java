package com.bkanent.listing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ListingStatusRecordEntity 房源状态历史实体（listing_status_record）。
 * 追加型审计历史：仅允许插入，不提供更新与删除；首次登记 fromStatus 为 NULL。
 */
@TableName("listing_status_record")
public class ListingStatusRecordEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务属性：id。
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**
     * 业务属性：listingId。
     */
    private Long listingId;
    /**
     * 业务属性：fromStatus（转换前状态；首次登记为 NULL）。
     */
    private String fromStatus;
    /**
     * 业务属性：toStatus（转换后状态）。
     */
    private String toStatus;
    /**
     * 业务属性：action（REGISTER/LIST/DELIST/SOLD_CONFIRM/STATUS_CHANGE）。
     */
    private String action;
    /**
     * 业务属性：reason。
     */
    private String reason;
    /**
     * 业务属性：operatorUserId。
     */
    private Long operatorUserId;
    /**
     * 业务属性：requestId（幂等键，无请求标识时为生成的 UUID）。
     */
    private String requestId;
    /**
     * 业务属性：createdAt。
     */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public void setFromStatus(String fromStatus) {
        this.fromStatus = fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public void setToStatus(String toStatus) {
        this.toStatus = toStatus;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Long getOperatorUserId() {
        return operatorUserId;
    }

    public void setOperatorUserId(Long operatorUserId) {
        this.operatorUserId = operatorUserId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
