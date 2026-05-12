package com.bkanent.marketing.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 营销内容实体。
 */
@TableName("marketing_content")
public class MarketingContentEntity extends BaseEntity {

    /**
     * 业务属性：listingId。
     */
    private Long listingId;
    /**
     * 业务属性：platform。
     */
    private String platform;
    /**
     * 业务属性：title。
     */
    private String title;
    /**
     * 业务属性：contentType。
     */
    private String contentType;
    /**
     * 业务属性：copywriting。
     */
    private String copywriting;

    @TableField("asset_urls")
    /**
     * 业务属性：assetUrls。
     */
    private String assetUrls;

    /**
     * 业务属性：coverImageUrl。
     */
    private String coverImageUrl;
    /**
     * 业务属性：videoUrl。
     */
    private String videoUrl;
    /**
     * 业务属性：versionNo。
     */
    private Integer versionNo;
    /**
     * 业务属性：parentContentId。
     */
    private Long parentContentId;
    /**
     * 业务属性：platformVariant。
     */
    private String platformVariant;
    /**
     * 业务属性：tags。
     */
    private String tags;
    /**
     * 业务属性：auditStatus。
     */
    private String auditStatus;
    /**
     * 业务属性：status。
     */
    private String status;
    /**
     * 业务属性：publishMessage。
     */
    private String publishMessage;
    /**
     * 业务属性：externalPublishId。
     */
    private String externalPublishId;
    /**
     * 业务属性：publishTime。
     */
    private LocalDateTime publishTime;

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
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

    public String getCoverImageUrl() {
        return coverImageUrl;
    }

    public void setCoverImageUrl(String coverImageUrl) {
        this.coverImageUrl = coverImageUrl;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public void setVideoUrl(String videoUrl) {
        this.videoUrl = videoUrl;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public Long getParentContentId() {
        return parentContentId;
    }

    public void setParentContentId(Long parentContentId) {
        this.parentContentId = parentContentId;
    }

    public String getPlatformVariant() {
        return platformVariant;
    }

    public void setPlatformVariant(String platformVariant) {
        this.platformVariant = platformVariant;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getAuditStatus() {
        return auditStatus;
    }

    public void setAuditStatus(String auditStatus) {
        this.auditStatus = auditStatus;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPublishMessage() {
        return publishMessage;
    }

    public void setPublishMessage(String publishMessage) {
        this.publishMessage = publishMessage;
    }

    public String getExternalPublishId() {
        return externalPublishId;
    }

    public void setExternalPublishId(String externalPublishId) {
        this.externalPublishId = externalPublishId;
    }

    public LocalDateTime getPublishTime() {
        return publishTime;
    }

    public void setPublishTime(LocalDateTime publishTime) {
        this.publishTime = publishTime;
    }
}
