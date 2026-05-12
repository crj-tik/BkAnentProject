package com.bkanent.notification.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 通知消息记录实体。
 */
@TableName("notification_record")
public class NotificationRecordEntity extends BaseEntity {

    /**
     * 业务属性：recipientUserId。
     */
    private Long recipientUserId;
    /**
     * 业务属性：channel。
     */
    private String channel;
    /**
     * 业务属性：sceneCode。
     */
    private String sceneCode;
    /**
     * 业务属性：title。
     */
    private String title;
    /**
     * 业务属性：content。
     */
    private String content;
    /**
     * 业务属性：receiverAddress。
     */
    private String receiverAddress;
    /**
     * 业务属性：externalWebhook。
     */
    private String externalWebhook;
    /**
     * 业务属性：sendStatus。
     */
    private String sendStatus;
    /**
     * 业务属性：readStatus。
     */
    private String readStatus;
    /**
     * 业务属性：readTime。
     */
    private LocalDateTime readTime;
    /**
     * 业务属性：sendTime。
     */
    private LocalDateTime sendTime;
    /**
     * 业务属性：externalMessageId。
     */
    private String externalMessageId;
    /**
     * 业务属性：errorMessage。
     */
    private String errorMessage;

    public Long getRecipientUserId() {
        return recipientUserId;
    }

    public void setRecipientUserId(Long recipientUserId) {
        this.recipientUserId = recipientUserId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSceneCode() {
        return sceneCode;
    }

    public void setSceneCode(String sceneCode) {
        this.sceneCode = sceneCode;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReceiverAddress() {
        return receiverAddress;
    }

    public void setReceiverAddress(String receiverAddress) {
        this.receiverAddress = receiverAddress;
    }

    public String getExternalWebhook() {
        return externalWebhook;
    }

    public void setExternalWebhook(String externalWebhook) {
        this.externalWebhook = externalWebhook;
    }

    public String getSendStatus() {
        return sendStatus;
    }

    public void setSendStatus(String sendStatus) {
        this.sendStatus = sendStatus;
    }

    public String getReadStatus() {
        return readStatus;
    }

    public void setReadStatus(String readStatus) {
        this.readStatus = readStatus;
    }

    public LocalDateTime getReadTime() {
        return readTime;
    }

    public void setReadTime(LocalDateTime readTime) {
        this.readTime = readTime;
    }

    public LocalDateTime getSendTime() {
        return sendTime;
    }

    public void setSendTime(LocalDateTime sendTime) {
        this.sendTime = sendTime;
    }

    public String getExternalMessageId() {
        return externalMessageId;
    }

    public void setExternalMessageId(String externalMessageId) {
        this.externalMessageId = externalMessageId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
