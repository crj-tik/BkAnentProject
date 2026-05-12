package com.bkanent.customer.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 经纪人跟进记录实体。
 */
@TableName("customer_follow_record")
public class CustomerFollowRecordEntity extends BaseEntity {

    /**
     * 业务属性：customerId。
     */
    private Long customerId;
    /**
     * 业务属性：brokerId。
     */
    private Long brokerId;
    /**
     * 业务属性：followType。
     */
    private String followType;
    /**
     * 业务属性：content。
     */
    private String content;
    /**
     * 业务属性：resultTag。
     */
    private String resultTag;
    /**
     * 业务属性：nextFollowTime。
     */
    private LocalDateTime nextFollowTime;

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getBrokerId() {
        return brokerId;
    }

    public void setBrokerId(Long brokerId) {
        this.brokerId = brokerId;
    }

    public String getFollowType() {
        return followType;
    }

    public void setFollowType(String followType) {
        this.followType = followType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getResultTag() {
        return resultTag;
    }

    public void setResultTag(String resultTag) {
        this.resultTag = resultTag;
    }

    public LocalDateTime getNextFollowTime() {
        return nextFollowTime;
    }

    public void setNextFollowTime(LocalDateTime nextFollowTime) {
        this.nextFollowTime = nextFollowTime;
    }
}
