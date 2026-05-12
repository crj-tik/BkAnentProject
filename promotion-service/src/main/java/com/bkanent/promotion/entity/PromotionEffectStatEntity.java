package com.bkanent.promotion.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;

/**
 * 宣传效果统计实体。
 */
@TableName("promotion_effect_stat")
public class PromotionEffectStatEntity extends BaseEntity {

    /**
     * 业务属性：publishRecordId。
     */
    private Long publishRecordId;
    /**
     * 业务属性：contentId。
     */
    private Long contentId;
    /**
     * 业务属性：listingId。
     */
    private Long listingId;
    /**
     * 业务属性：platform。
     */
    private String platform;
    /**
     * 业务属性：exposureCount。
     */
    private Integer exposureCount;
    /**
     * 业务属性：clickCount。
     */
    private Integer clickCount;
    /**
     * 业务属性：privateMessageCount。
     */
    private Integer privateMessageCount;
    /**
     * 业务属性：leadCount。
     */
    private Integer leadCount;
    /**
     * 业务属性：ctrValue。
     */
    private BigDecimal ctrValue;
    /**
     * 业务属性：conversionRate。
     */
    private BigDecimal conversionRate;
    /**
     * 业务属性：roiValue。
     */
    private BigDecimal roiValue;
    /**
     * 业务属性：statDate。
     */
    private String statDate;

    public Long getPublishRecordId() {
        return publishRecordId;
    }

    public void setPublishRecordId(Long publishRecordId) {
        this.publishRecordId = publishRecordId;
    }

    public Long getContentId() {
        return contentId;
    }

    public void setContentId(Long contentId) {
        this.contentId = contentId;
    }

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

    public Integer getExposureCount() {
        return exposureCount;
    }

    public void setExposureCount(Integer exposureCount) {
        this.exposureCount = exposureCount;
    }

    public Integer getClickCount() {
        return clickCount;
    }

    public void setClickCount(Integer clickCount) {
        this.clickCount = clickCount;
    }

    public Integer getPrivateMessageCount() {
        return privateMessageCount;
    }

    public void setPrivateMessageCount(Integer privateMessageCount) {
        this.privateMessageCount = privateMessageCount;
    }

    public Integer getLeadCount() {
        return leadCount;
    }

    public void setLeadCount(Integer leadCount) {
        this.leadCount = leadCount;
    }

    public BigDecimal getCtrValue() {
        return ctrValue;
    }

    public void setCtrValue(BigDecimal ctrValue) {
        this.ctrValue = ctrValue;
    }

    public BigDecimal getConversionRate() {
        return conversionRate;
    }

    public void setConversionRate(BigDecimal conversionRate) {
        this.conversionRate = conversionRate;
    }

    public BigDecimal getRoiValue() {
        return roiValue;
    }

    public void setRoiValue(BigDecimal roiValue) {
        this.roiValue = roiValue;
    }

    public String getStatDate() {
        return statDate;
    }

    public void setStatDate(String statDate) {
        this.statDate = statDate;
    }
}
