package com.bkanent.listing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * ListingPrivateInfoEntity 房源敏感资料实体（listing_private_info）。
 * 产权证号以 AES-256-GCM 认证加密保存（nonce || ciphertext || tag），
 * certificateKeyId 记录加密密钥版本支持轮换；明文证号与密钥不得写日志。
 */
@TableName("listing_private_info")
public class ListingPrivateInfoEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务属性：listingId（主键，即房源 ID）。
     */
    @TableId(value = "listing_id", type = IdType.INPUT)
    private Long listingId;
    /**
     * 业务属性：certificateNoCiphertext（nonce || ciphertext || tag，AES-256-GCM）。
     */
    private byte[] certificateNoCiphertext;
    /**
     * 业务属性：certificateKeyId（加密密钥标识，支持轮换）。
     */
    private String certificateKeyId;
    /**
     * 业务属性：createdAt。
     */
    private LocalDateTime createdAt;
    /**
     * 业务属性：updatedAt。
     */
    private LocalDateTime updatedAt;

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public byte[] getCertificateNoCiphertext() {
        return certificateNoCiphertext;
    }

    public void setCertificateNoCiphertext(byte[] certificateNoCiphertext) {
        this.certificateNoCiphertext = certificateNoCiphertext;
    }

    public String getCertificateKeyId() {
        return certificateKeyId;
    }

    public void setCertificateKeyId(String certificateKeyId) {
        this.certificateKeyId = certificateKeyId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
