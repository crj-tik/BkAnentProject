package com.bkanent.listing.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.listing.config.ListingPrivateInfoProperties;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.entity.ListingPrivateInfoEntity;
import com.bkanent.listing.mapper.ListingPrivateInfoMapper;
import com.bkanent.listing.service.ListingDomainService;
import com.bkanent.listing.service.ListingPrivateInfoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * ListingPrivateInfoServiceImpl 房源敏感资料受管加密服务实现。
 * AES-256-GCM：密钥取配置 Base64 解码后至少 32 字节（取前 32 字节），未配置或无效时失败关闭；
 * 每次加密生成随机 12 字节 nonce，密文格式 = nonce || ciphertext || tag；
 * certificateKeyId 记录密钥版本，读取时版本不匹配失败关闭（轮换需先重加密）。
 */
@Service
public class ListingPrivateInfoServiceImpl implements ListingPrivateInfoService {

    /**
     * 常量：NONCE_LENGTH_BYTES（GCM 推荐 nonce 长度）。
     */
    private static final int NONCE_LENGTH_BYTES = 12;
    /**
     * 常量：GCM_TAG_BITS（认证标签长度）。
     */
    private static final int GCM_TAG_BITS = 128;
    /**
     * 常量：AES_KEY_LENGTH_BYTES（AES-256 密钥长度）。
     */
    private static final int AES_KEY_LENGTH_BYTES = 32;

    /**
     * 字段：log。
     */
    private static final Logger log = LoggerFactory.getLogger(ListingPrivateInfoServiceImpl.class);
    /**
     * 字段：mapper。
     */
    private final ListingPrivateInfoMapper mapper;
    /**
     * 字段：domain（同事务置空 listing_info.certificate_no 明文）。
     */
    private final ListingDomainService domain;
    /**
     * 字段：properties（加密密钥与版本配置）。
     */
    private final ListingPrivateInfoProperties properties;
    /**
     * 字段：random（nonce 生成）。
     */
    private final SecureRandom random = new SecureRandom();

    /**
     * 构造 ListingPrivateInfoServiceImpl 实例。
     */
    public ListingPrivateInfoServiceImpl(ListingPrivateInfoMapper mapper, ListingDomainService domain,
                                          ListingPrivateInfoProperties properties) {
        this.mapper = mapper;
        this.domain = domain;
        this.properties = properties;
    }

    /**
     * 加密保存证号：随机 nonce 加密落库（记录密钥版本），同事务置空明文列。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveCertificate(Long listingId, String certificateNo) {
        if (listingId == null || listingId <= 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "listingId");
        }
        if (!StringUtils.hasText(certificateNo)) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "certificateNo");
        }
        byte[] key = requireKey();
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        random.nextBytes(nonce);
        byte[] sealed = seal(key, nonce, certificateNo.trim().getBytes(StandardCharsets.UTF_8));
        ListingPrivateInfoEntity row = new ListingPrivateInfoEntity();
        row.setListingId(listingId);
        row.setCertificateNoCiphertext(sealed);
        row.setCertificateKeyId(properties.getKeyId());
        if (mapper.selectById(listingId) == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        // 同事务置空明文：新写入不保留明文；存量批量清理属部署期受控操作（backfill.md 2.2）。
        domain.update(null, new LambdaUpdateWrapper<ListingEntity>()
                .eq(ListingEntity::getId, listingId)
                .set(ListingEntity::getCertificateNo, null));
        log.info("证号加密保存成功，listingId={}，keyId={}，cipherLength={}",
                listingId, properties.getKeyId(), sealed.length);
    }

    /**
     * 解密返回证号；无私有行或密文返回 null；密钥版本不匹配失败关闭。
     */
    @Override
    public String readCertificate(Long listingId) {
        if (listingId == null || listingId <= 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "listingId");
        }
        ListingPrivateInfoEntity row = mapper.selectById(listingId);
        if (row == null || row.getCertificateNoCiphertext() == null) {
            return null;
        }
        if (row.getCertificateKeyId() != null && properties.getKeyId() != null
                && !row.getCertificateKeyId().equals(properties.getKeyId())) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "certificateKeyId");
        }
        return new String(open(requireKey(), row.getCertificateNoCiphertext()), StandardCharsets.UTF_8);
    }

    /**
     * 读取并校验加密密钥：未配置、非法 Base64 或不足 32 字节均失败关闭，不降级明文。
     */
    private byte[] requireKey() {
        String encoded = properties.getEncryptionKey();
        if (!StringUtils.hasText(encoded)) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT,
                    "listing.private-info.encryption-key 未配置，敏感资料写入失败关闭");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException failure) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT,
                    "listing.private-info.encryption-key 不是有效 Base64，敏感资料写入失败关闭");
        }
        if (decoded.length < AES_KEY_LENGTH_BYTES) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT,
                    "listing.private-info.encryption-key 长度不足 32 字节，敏感资料写入失败关闭");
        }
        return Arrays.copyOf(decoded, AES_KEY_LENGTH_BYTES);
    }

    /**
     * AES-256-GCM 加密：返回 nonce || ciphertext || tag。
     */
    private static byte[] seal(byte[] key, byte[] nonce, byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] sealed = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, sealed, 0, nonce.length);
            System.arraycopy(ciphertext, 0, sealed, nonce.length, ciphertext.length);
            return sealed;
        } catch (GeneralSecurityException failure) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "certificateCipher");
        }
    }

    /**
     * AES-256-GCM 解密：入参为 nonce || ciphertext || tag；认证失败抛出异常。
     */
    private static byte[] open(byte[] key, byte[] sealed) {
        if (sealed.length <= NONCE_LENGTH_BYTES) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "certificateCipher");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, Arrays.copyOfRange(sealed, 0, NONCE_LENGTH_BYTES)));
            return cipher.doFinal(Arrays.copyOfRange(sealed, NONCE_LENGTH_BYTES, sealed.length));
        } catch (GeneralSecurityException failure) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "certificateCipher");
        }
    }
}
