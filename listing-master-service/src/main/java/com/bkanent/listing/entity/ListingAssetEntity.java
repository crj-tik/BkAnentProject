package com.bkanent.listing.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * ListingAssetEntity 房源素材实体（listing_asset）。
 * 逐项素材行：assetType / visibility / sortOrder / status；storageKey 仅保留原值（受管文件键或历史 URL），
 * 服务端不抓取任何 URL。deleted 为逻辑删除标记（BaseEntity @TableLogic），退役即置 1。
 */
@TableName("listing_asset")
public class ListingAssetEntity extends BaseEntity {

    /**
     * 业务属性：listingId（所属房源）。
     */
    private Long listingId;
    /**
     * 业务属性：assetType（IMAGE/FLOOR_PLAN/VIDEO/CERTIFICATE/CONTRACT）。
     */
    private String assetType;
    /**
     * 业务属性：storageKey（受管文件键或历史 URL 原值，不主动抓取）。
     */
    private String storageKey;
    /**
     * 业务属性：visibility（BUYER/INTERNAL/SENSITIVE）。
     */
    private String visibility;
    /**
     * 业务属性：sortOrder（同类型内展示顺序，越小越靠前）。
     */
    private Integer sortOrder;
    /**
     * 业务属性：status（ACTIVE/PENDING_REVIEW/RETIRED）。
     */
    private String status;
    /**
     * 业务属性：createdBy（登记操作人用户 ID）。
     */
    private Long createdBy;

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public String getAssetType() {
        return assetType;
    }

    public void setAssetType(String assetType) {
        this.assetType = assetType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
