package com.bkanent.memory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("user_preference_memory")
public class UserPreferenceMemoryEntity extends BaseEntity {

    private String userId;
    private String preferenceKey;
    private String preferenceValue;
    private String category;
    private BigDecimal confidence;
    private String evidence;
    private String sourceSessionId;
    private LocalDateTime lastObservedAt;
    private Integer observationCount;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPreferenceKey() {
        return preferenceKey;
    }

    public void setPreferenceKey(String preferenceKey) {
        this.preferenceKey = preferenceKey;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public void setPreferenceValue(String preferenceValue) {
        this.preferenceValue = preferenceValue;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public void setSourceSessionId(String sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
    }

    public LocalDateTime getLastObservedAt() {
        return lastObservedAt;
    }

    public void setLastObservedAt(LocalDateTime lastObservedAt) {
        this.lastObservedAt = lastObservedAt;
    }

    public Integer getObservationCount() {
        return observationCount;
    }

    public void setObservationCount(Integer observationCount) {
        this.observationCount = observationCount;
    }
}
