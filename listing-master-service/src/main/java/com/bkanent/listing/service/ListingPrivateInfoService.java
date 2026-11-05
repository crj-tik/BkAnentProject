package com.bkanent.listing.service;

/**
 * ListingPrivateInfoService 房源敏感资料（产权证号）受管加密服务。
 * AES-256-GCM 认证加密：密文 = nonce(12) || ciphertext || tag；每条随机 nonce；
 * 密钥来自 listing.private-info.encryption-key（未配置时敏感写入失败关闭，不降级明文）；
 * certificateKeyId 记录密钥版本支持轮换；日志不打印明文证号与密钥。
 */
public interface ListingPrivateInfoService {

    /**
     * 加密保存证号并同事务把 listing_info.certificate_no 置空（新写入不保留明文；
     * 存量明文批量清理属部署期受控操作，见 backfill.md 2.2）。
     */
    void saveCertificate(Long listingId, String certificateNo);

    /**
     * 解密返回证号；无私有行或密文时返回 null。仅限已持有私有详情权限的调用方使用。
     */
    String readCertificate(Long listingId);
}
