package com.bkanent.listing.service;

import com.bkanent.common.rag.AuthRagPrincipal;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagIndexEvent;
import com.bkanent.listing.config.ListingPrivateInfoProperties;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.customer.CustomerProfileVerifyClient;
import com.bkanent.listing.mapper.ListingPriceRecordMapper;
import com.bkanent.listing.mapper.ListingStatusRecordMapper;
import com.bkanent.listing.model.ListingBuyerViewResponse;
import com.bkanent.listing.model.ListingDetailResponse;
import com.bkanent.listing.model.ListingOcrRecognizeRequest;
import com.bkanent.listing.model.ListingVerifyRequest;
import com.bkanent.listing.rag.ListingAuthzScopeFixture;
import com.bkanent.listing.rag.ListingRagIdentity;
import com.bkanent.listing.rag.ListingRagOutbox;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import com.bkanent.listing.service.impl.ListingAssetServiceImpl;
import com.bkanent.listing.service.impl.ListingManagementServiceImpl;
import com.bkanent.listing.service.impl.ListingPrivateInfoServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 3.7 敏感资料与模拟核验修正：AES-256-GCM 加密往返、失败关闭、nonce 唯一、密钥版本、核验加密保存、OCR 占位修正、买方/RAG 投影无敏感。 */
class ListingPrivateInfoServiceTest {

    private static final String CERTIFICATE = "CERT-10086-私有";

    private ListingMybatisH2Fixture fixture;
    private ListingPrivateInfoProperties properties;

    @BeforeEach
    void setup() {
        fixture = new ListingMybatisH2Fixture();
        fixture.insertListing(11L, 9L, "ON_SALE", "2026-11-05 10:00:00",
                BigDecimal.ONE, BigDecimal.valueOf(100000L), BigDecimal.TEN, null, null, "两室", 0);
        properties = new ListingPrivateInfoProperties();
        properties.setEncryptionKey(randomKeyBase64());
        properties.setKeyId("test-key");
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private static String randomKeyBase64() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private ListingPrivateInfoServiceImpl service() {
        return new ListingPrivateInfoServiceImpl(fixture.privateInfoMapper, fixture.newDomainService(), properties);
    }

    private ListingManagementServiceImpl management(ListingPrivateInfoServiceImpl privateInfo,
                                                    ListingAssetServiceImpl assets,
                                                    ListingRagOutbox outbox,
                                                    ListingRagIdentity identity) {
        return new ListingManagementServiceImpl(fixture.newDomainService(), new ListingConverter(),
                mock(ListingSearchService.class), new ListingKnowledgeDocumentAssembler(), outbox, identity,
                mock(ListingPriceRecordMapper.class), mock(ListingStatusRecordMapper.class),
                mock(ListingCommandReceiptService.class), mock(CustomerProfileVerifyClient.class),
                assets, privateInfo);
    }

    private static ListingRagIdentity identity() {
        var identity = mock(ListingRagIdentity.class);
        when(identity.requireReader(any())).thenReturn(new AuthRagPrincipal(7L, "ADMIN", true, true, Long.MAX_VALUE));
        return identity;
    }

    @Test
    void saveEncryptsClearsPlaintextAndReadRoundTrips() {
        fixture.jdbc.update("UPDATE listing_info SET certificate_no=? WHERE id=11", CERTIFICATE);
        service().saveCertificate(11L, CERTIFICATE);
        assertEquals(CERTIFICATE, service().readCertificate(11L));
        // 明文同事务置空（新写入不保留明文）。
        assertNull(fixture.jdbc.queryForObject("SELECT certificate_no FROM listing_info WHERE id=11", String.class));
        // 密文行：密钥版本记录，密文不等于明文字节且包含 nonce(12)+tag(16)。
        byte[] ciphertext = fixture.jdbc.queryForObject(
                "SELECT certificate_no_ciphertext FROM listing_private_info WHERE listing_id=11", byte[].class);
        assertNotNull(ciphertext);
        assertTrue(ciphertext.length >= 28);
        assertNotEquals(CERTIFICATE.getBytes(StandardCharsets.UTF_8).length, ciphertext.length);
        assertEquals("test-key", fixture.jdbc.queryForObject(
                "SELECT certificate_key_id FROM listing_private_info WHERE listing_id=11", String.class));
    }

    @Test
    void saveFailsClosedWhenKeyMissingOrInvalid() {
        fixture.jdbc.update("UPDATE listing_info SET certificate_no=? WHERE id=11", CERTIFICATE);
        properties.setEncryptionKey(null);
        assertEquals(RagErrorCode.INVALID_ARGUMENT,
                assertThrows(RagException.class, () -> service().saveCertificate(11L, CERTIFICATE)).code());
        properties.setEncryptionKey("not-valid-base64!!");
        assertEquals(RagErrorCode.INVALID_ARGUMENT,
                assertThrows(RagException.class, () -> service().saveCertificate(11L, CERTIFICATE)).code());
        properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertEquals(RagErrorCode.INVALID_ARGUMENT,
                assertThrows(RagException.class, () -> service().saveCertificate(11L, CERTIFICATE)).code());
        // 失败关闭：不落密文行，明文保持原值（不降级明文写入密文表）。
        assertEquals(0, fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM listing_private_info WHERE listing_id=11", Long.class).longValue());
        assertEquals(CERTIFICATE, fixture.jdbc.queryForObject(
                "SELECT certificate_no FROM listing_info WHERE id=11", String.class));
    }

    @Test
    void nonceDiffersPerSave() {
        service().saveCertificate(11L, CERTIFICATE);
        byte[] first = fixture.jdbc.queryForObject(
                "SELECT certificate_no_ciphertext FROM listing_private_info WHERE listing_id=11", byte[].class);
        service().saveCertificate(11L, CERTIFICATE);
        byte[] second = fixture.jdbc.queryForObject(
                "SELECT certificate_no_ciphertext FROM listing_private_info WHERE listing_id=11", byte[].class);
        assertFalse(java.util.Arrays.equals(first, second));
        assertEquals(CERTIFICATE, service().readCertificate(11L));
    }

    @Test
    void keyVersionMismatchFailsClosedAndTamperingFailsAuthentication() {
        service().saveCertificate(11L, CERTIFICATE);
        // 轮换后读取：密钥版本不匹配失败关闭，不以旧密文冒充可读。
        properties.setKeyId("rotated-key");
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(RagException.class, () -> service().readCertificate(11L)).code());
        properties.setKeyId("test-key");
        assertEquals(CERTIFICATE, service().readCertificate(11L));
        // 篡改密文（经 mapper 更新以同步会话缓存）：GCM 认证失败。
        com.bkanent.listing.entity.ListingPrivateInfoEntity row = fixture.privateInfoMapper.selectById(11L);
        row.getCertificateNoCiphertext()[row.getCertificateNoCiphertext().length - 1] ^= 0x01;
        fixture.privateInfoMapper.updateById(row);
        assertEquals(RagErrorCode.INVALID_ARGUMENT,
                assertThrows(RagException.class, () -> service().readCertificate(11L)).code());
    }

    @Test
    void readReturnsNullWithoutRow() {
        assertNull(service().readCertificate(11L));
        assertNull(service().readCertificate(999L));
    }

    @Test
    void verifyAuthenticityEncryptsCertificateAndMarksManualInternal() {
        ListingPrivateInfoServiceImpl privateInfo = service();
        ListingManagementServiceImpl management = management(privateInfo,
                new ListingAssetServiceImpl(fixture.assetMapper, fixture.newDomainService()),
                mock(ListingRagOutbox.class), identity());
        try (var ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.verify.own"), List.of("listing.verify.own"), Map.of("BROKER", 9L))) {
            ListingDetailResponse response = management.verifyAuthenticity(11L,
                    new ListingVerifyRequest(null, CERTIFICATE, "业主甲"));
            assertEquals("VERIFIED", response.verificationStatus());
            assertEquals("manual-internal", response.verificationSource());
            assertTrue(response.verificationRemark().contains("内部人工登记核验"));
            assertEquals(CERTIFICATE, response.certificateNo());
        }
        // 核验通过即加密保存：密文行存在、明文置空、业主名保留。
        assertEquals(CERTIFICATE, privateInfo.readCertificate(11L));
        assertNull(fixture.jdbc.queryForObject("SELECT certificate_no FROM listing_info WHERE id=11", String.class));
        assertEquals("业主甲", fixture.jdbc.queryForObject("SELECT owner_name FROM listing_info WHERE id=11", String.class));

        // 加密后重复核验（不带证号）：证号从密文解密回填，仍可核验通过。
        try (var ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.verify.own"), List.of("listing.verify.own"), Map.of("BROKER", 9L))) {
            ListingDetailResponse again = management.verifyAuthenticity(11L, new ListingVerifyRequest(null, null, null));
            assertEquals("VERIFIED", again.verificationStatus());
            assertEquals(CERTIFICATE, again.certificateNo());
        }
    }

    @Test
    void recognizeOcrKeepsCertificateEmptyAndMarksSimulated() {
        ListingManagementServiceImpl management = management(service(),
                new ListingAssetServiceImpl(fixture.assetMapper, fixture.newDomainService()),
                mock(ListingRagOutbox.class), identity());
        try (var ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.ocr.own"), List.of("listing.ocr.own"), Map.of("BROKER", 9L))) {
            ListingDetailResponse response = management.recognizeOcr(11L,
                    new ListingOcrRecognizeRequest("cert-url", "contract-url"));
            // 不再默认填 OCR-{id} 占位证号/占位业主名：未确认事实保持未知。
            assertNull(response.certificateNo());
            assertNull(response.ownerName());
            assertEquals("DONE", response.ocrStatus());
            assertNotEquals("VERIFIED", response.verificationStatus());
            assertTrue(response.verificationRemark().contains("模拟 OCR 占位"));
            assertTrue(response.verificationRemark().contains("人工登记"));
        }
        assertNull(fixture.jdbc.queryForObject("SELECT certificate_no FROM listing_info WHERE id=11", String.class));
        assertNull(fixture.jdbc.queryForObject("SELECT owner_name FROM listing_info WHERE id=11", String.class));
        assertEquals("cert-url", fixture.jdbc.queryForObject(
                "SELECT property_certificate_url FROM listing_info WHERE id=11", String.class));
    }

    @Test
    void buyerViewAndRagProjectionContainNoSensitiveData() throws Exception {
        fixture.jdbc.update("UPDATE listing_info SET owner_name='业主甲', certificate_no=?, "
                + "property_certificate_url='cert-url', contract_url='contract-url', image_urls='img-1' WHERE id=11",
                CERTIFICATE);
        ListingAssetServiceImpl assets = new ListingAssetServiceImpl(fixture.assetMapper, fixture.newDomainService());
        assets.replaceAssets(11L, List.of(
                new com.bkanent.listing.model.ListingAssetUpsertItem("IMAGE", "buyer-img", "BUYER", 0, "ACTIVE"),
                new com.bkanent.listing.model.ListingAssetUpsertItem("IMAGE", "pending-img", "BUYER", 1, "PENDING_REVIEW"),
                new com.bkanent.listing.model.ListingAssetUpsertItem("IMAGE", "internal-img", "INTERNAL", 2, "ACTIVE"),
                new com.bkanent.listing.model.ListingAssetUpsertItem("CERTIFICATE", "cert-url", "SENSITIVE", 0, "ACTIVE")),
                java.util.Set.of("IMAGE", "CERTIFICATE"), 123L);
        ListingManagementServiceImpl management = management(service(), assets, mock(ListingRagOutbox.class), identity());
        try (var ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.read"), List.of("listing.read"), Map.of("BROKER", 9L))) {
            ListingBuyerViewResponse buyer = management.getBuyerDetail(11L);
            assertNotNull(buyer);
            // 买方投影：素材仅 BUYER 且 ACTIVE（不含 INTERNAL/SENSITIVE/PENDING_REVIEW）。
            assertEquals(1, buyer.assets().size());
            assertEquals("buyer-img", buyer.assets().get(0).storageKey());
            // 序列化后不含业主名/证号/证件与合同 URL。
            String body = new ObjectMapper().writeValueAsString(buyer);
            assertFalse(body.contains(CERTIFICATE));
            assertFalse(body.contains("业主甲"));
            assertFalse(body.contains("cert-url"));
            assertFalse(body.contains("contract-url"));
        }
        // RAG 文档投影（ListingRagOutbox.document）不含证号与业主名。
        fixture.jdbc.execute("CREATE TABLE listing_rag_revision(listing_id BIGINT PRIMARY KEY, revision BIGINT DEFAULT 0, "
                + "tombstone BOOLEAN DEFAULT FALSE, content_hash VARCHAR(64), latest_event_id VARCHAR(64), "
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        fixture.jdbc.execute("CREATE TABLE listing_rag_outbox(event_id VARCHAR(64) PRIMARY KEY, listing_id BIGINT, "
                + "revision BIGINT, content_hash VARCHAR(64), payload CLOB, state VARCHAR(32), attempts INT, "
                + "next_attempt_at TIMESTAMP, lease_until TIMESTAMP, lease_token VARCHAR(64), error_code VARCHAR(64), "
                + "created_at TIMESTAMP, UNIQUE(listing_id, revision))");
        ListingRagProperties ragProperties = new ListingRagProperties();
        ragProperties.setSecret("rag-test-secret-0123456789abcdef");
        ListingRagOutbox outbox = new ListingRagOutbox(fixture.jdbc, fixture.listingMapper,
                new ObjectMapper().registerModule(new JavaTimeModule()), new ListingConverter(),
                new ListingKnowledgeDocumentAssembler(), ragProperties);
        RagIndexEvent event = outbox.publishCurrent(11L);
        String payload = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(event);
        assertFalse(payload.contains(CERTIFICATE));
        assertFalse(payload.contains("业主甲"));
        assertFalse(payload.contains("cert-url"));
        assertFalse(payload.contains("contract-url"));
    }
}
