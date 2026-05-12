package com.bkanent.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 配置。
 */
@ConfigurationProperties(prefix = "media.rocketmq")
public class MediaRocketMqProperties {

    /**
     * 业务属性：generateTopic。
     */
    private String generateTopic = "media_generate_task";
    /**
     * 业务属性：consumerGroup。
     */
    private String consumerGroup = "media-worker-consumer-group";
    /**
     * 业务属性：producerGroup。
     */
    private String producerGroup = "media-worker-producer-group";
    /**
     * 业务属性：syncWaitTimeoutMs。
     */
    private long syncWaitTimeoutMs = 15000L;
    /**
     * 业务属性：syncPollIntervalMs。
     */
    private long syncPollIntervalMs = 200L;

    public String getGenerateTopic() {
        return generateTopic;
    }

    public void setGenerateTopic(String generateTopic) {
        this.generateTopic = generateTopic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getProducerGroup() {
        return producerGroup;
    }

    public void setProducerGroup(String producerGroup) {
        this.producerGroup = producerGroup;
    }

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
}
