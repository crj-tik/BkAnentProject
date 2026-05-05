package com.bkanent.marketing.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

@TableName("marketing_content")
public class MarketingContentEntity extends BaseEntity {

    private Long listingId;
    private String platform;
    private String copywriting;

    @TableField("asset_urls")
    private String assetUrls;

    private String status;

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getCopywriting() {
        return copywriting;
    }

    public void setCopywriting(String copywriting) {
        this.copywriting = copywriting;
    }

    public String getAssetUrls() {
        return assetUrls;
    }

    public void setAssetUrls(String assetUrls) {
        this.assetUrls = assetUrls;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
