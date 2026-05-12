package com.bkanent.promotion.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

/**
 * 品牌素材库实体。
 */
@TableName("brand_asset")
public class BrandAssetEntity extends BaseEntity {

    /**
     * 业务属性：assetType。
     */
    private String assetType;
    /**
     * 业务属性：assetName。
     */
    private String assetName;
    /**
     * 业务属性：assetUrl。
     */
    private String assetUrl;
    /**
     * 业务属性：platformScope。
     */
    private String platformScope;
    /**
     * 业务属性：tagNames。
     */
    private String tagNames;
    /**
     * 业务属性：status。
     */
    private String status;
    /**
     * 业务属性：remark。
     */
    private String remark;

    public String getAssetType() {
        return assetType;
    }

    public void setAssetType(String assetType) {
        this.assetType = assetType;
    }

    public String getAssetName() {
        return assetName;
    }

    public void setAssetName(String assetName) {
        this.assetName = assetName;
    }

    public String getAssetUrl() {
        return assetUrl;
    }

    public void setAssetUrl(String assetUrl) {
        this.assetUrl = assetUrl;
    }

    public String getPlatformScope() {
        return platformScope;
    }

    public void setPlatformScope(String platformScope) {
        this.platformScope = platformScope;
    }

    public String getTagNames() {
        return tagNames;
    }

    public void setTagNames(String tagNames) {
        this.tagNames = tagNames;
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
