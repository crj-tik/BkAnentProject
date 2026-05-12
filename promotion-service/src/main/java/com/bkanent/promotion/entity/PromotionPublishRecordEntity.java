package com.bkanent.promotion.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 宣传发布记录实体。
 */
@TableName("promotion_publish_record")
public class PromotionPublishRecordEntity extends BaseEntity {

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
     * 业务属性：channelAccount。
     */
    private String channelAccount;
    /**
     * 业务属性：publishStatus。
     */
    private String publishStatus;
    /**
     * 业务属性：externalPublishId。
     */
    private String externalPublishId;
    /**
     * 业务属性：publishTime。
     */
    private LocalDateTime publishTime;
    /**
     * 业务属性：publishMessage。
     */
    private String publishMessage;
    /**
     * 业务属性：costAmount。
     */
    private BigDecimal costAmount;
    /**
     * 业务属性：operatorName。
     */
    private String operatorName;

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

    public String getChannelAccount() {
        return channelAccount;
    }

    public void setChannelAccount(String channelAccount) {
        this.channelAccount = channelAccount;
    }

    public String getPublishStatus() {
        return publishStatus;
    }

    public void setPublishStatus(String publishStatus) {
        this.publishStatus = publishStatus;
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

    public String getPublishMessage() {
        return publishMessage;
    }

    public void setPublishMessage(String publishMessage) {
        this.publishMessage = publishMessage;
    }

    public BigDecimal getCostAmount() {
        return costAmount;
    }

    public void setCostAmount(BigDecimal costAmount) {
        this.costAmount = costAmount;
    }

    public String getOperatorName() {
        return operatorName;
    }

    public void setOperatorName(String operatorName) {
        this.operatorName = operatorName;
    }
}
