package com.bkanent.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 对象存储配置。
 */
@ConfigurationProperties(prefix = "media.minio")
public class MediaMinioProperties {

    /**
     * 业务属性：endpoint。
     */
    private String endpoint = "http://127.0.0.1:9000";
    /**
     * 业务属性：accessKey。
     */
    private String accessKey = "minioadmin";
    /**
     * 业务属性：secretKey。
     */
    private String secretKey = "minioadmin";
    /**
     * 业务属性：bucket。
     */
    private String bucket = "generated-assets";
    /**
     * 业务属性：publicBaseUrl。
     */
    private String publicBaseUrl = "https://minio.local";

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }
}
