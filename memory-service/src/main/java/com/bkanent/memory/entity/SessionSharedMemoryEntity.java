package com.bkanent.memory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.bkanent.common.model.BaseEntity;

@TableName("session_shared_memory")
public class SessionSharedMemoryEntity extends BaseEntity {

    private String sessionId;
    private String userId;
    private String memoryJson;
    private String summary;
    private String traceId;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMemoryJson() {
        return memoryJson;
    }

    public void setMemoryJson(String memoryJson) {
        this.memoryJson = memoryJson;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
