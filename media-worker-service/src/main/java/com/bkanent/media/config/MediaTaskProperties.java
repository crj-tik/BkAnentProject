package com.bkanent.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 媒体任务配置。
 */
@ConfigurationProperties(prefix = "media.task")
public class MediaTaskProperties {

    /**
     * 同步等待超时时间，单位毫秒。
     */
    /**
     * 业务属性：syncWaitTimeoutMs。
     */
    private long syncWaitTimeoutMs = 15000L;

    /**
     * 同步轮询间隔，单位毫秒。
     */
    /**
     * 业务属性：syncPollIntervalMs。
     */
    private long syncPollIntervalMs = 200L;

    /**
     * 默认生成视角列表。
     */
    /**
     * 业务属性：defaultAngles。
     */
    private List<String> defaultAngles = List.of("客厅视角", "卧室视角", "餐厅视角");

    public long getSyncWaitTimeoutMs() {
        return syncWaitTimeoutMs;
    }

    public void setSyncWaitTimeoutMs(long syncWaitTimeoutMs) {
        this.syncWaitTimeoutMs = syncWaitTimeoutMs;
    }

    public long getSyncPollIntervalMs() {
        return syncPollIntervalMs;
    }

    public void setSyncPollIntervalMs(long syncPollIntervalMs) {
        this.syncPollIntervalMs = syncPollIntervalMs;
    }

    public List<String> getDefaultAngles() {
        return defaultAngles;
    }

    public void setDefaultAngles(List<String> defaultAngles) {
        this.defaultAngles = defaultAngles;
    }
}
