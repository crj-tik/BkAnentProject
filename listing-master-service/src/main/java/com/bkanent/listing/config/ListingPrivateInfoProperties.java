package com.bkanent.listing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ListingPrivateInfoProperties 房源敏感资料加密配置。
 * encryptionKey 为 Base64 编码的 AES-256 密钥（解码后至少 32 字节，取前 32 字节）；
 * 未配置或无效时敏感写入失败关闭，不降级明文。keyId 记录密钥版本支持轮换。
 */
@Component
@ConfigurationProperties(prefix = "listing.private-info")
public class ListingPrivateInfoProperties {

    /**
     * 字段：encryptionKey（Base64 AES-256 密钥）。
     */
    private String encryptionKey;
    /**
     * 字段：keyId（密钥标识，默认 private-info-current）。
     */
    private String keyId = "private-info-current";

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }
}
