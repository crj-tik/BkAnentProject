package com.bkanent.listing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ListingPriceRecordEntity 房源价格历史实体（listing_price_record）。
 * 追加型审计历史：仅允许插入，不提供更新与删除；首次登记 oldPriceYuan 为 NULL。
 */
@TableName("listing_price_record")
public class ListingPriceRecordEntity implements Serializable {

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
     * 业务属性：oldPriceYuan（调价前挂牌价，CNY 元；首次登记为 NULL）。
     */
    private BigDecimal oldPriceYuan;
    /**
     * 业务属性：newPriceYuan（调价后挂牌价，CNY 元）。
     */
    private BigDecimal newPriceYuan;
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

    public BigDecimal getOldPriceYuan() {
        return oldPriceYuan;
    }

    public void setOldPriceYuan(BigDecimal oldPriceYuan) {
        this.oldPriceYuan = oldPriceYuan;
    }

    public BigDecimal getNewPriceYuan() {
        return newPriceYuan;
    }

    public void setNewPriceYuan(BigDecimal newPriceYuan) {
        this.newPriceYuan = newPriceYuan;
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
