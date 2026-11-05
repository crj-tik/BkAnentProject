package com.bkanent.listing.service;

import com.bkanent.common.rag.AuthRagPrincipal;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.customer.CustomerProfileVerifyClient;
import com.bkanent.listing.entity.ListingAssetEntity;
import com.bkanent.listing.mapper.ListingPriceRecordMapper;
import com.bkanent.listing.mapper.ListingStatusRecordMapper;
import com.bkanent.listing.model.ListingAssetBindRequest;
import com.bkanent.listing.model.ListingAssetUpsertItem;
import com.bkanent.listing.model.ListingDetailResponse;
import com.bkanent.listing.rag.ListingAuthzScopeFixture;
import com.bkanent.listing.rag.ListingRagIdentity;
import com.bkanent.listing.rag.ListingRagOutbox;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import com.bkanent.listing.service.impl.ListingAssetServiceImpl;
import com.bkanent.listing.service.impl.ListingManagementServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 3.6 素材逐项管理：绑定/替换/退役/可见性过滤/兼容端点委托/旧 URL 迁移幂等（真实 H2 SQL）。 */
class ListingAssetServiceTest {

    private ListingMybatisH2Fixture fixture;
    private ListingAssetServiceImpl assetService;

    @BeforeEach
    void setup() {
        fixture = new ListingMybatisH2Fixture();
        fixture.insertListing(11L, 9L, "ON_SALE", "2026-11-05 10:00:00",
                BigDecimal.ONE, BigDecimal.valueOf(100000L), BigDecimal.TEN, null, null, "两室", 0);
        assetService = new ListingAssetServiceImpl(fixture.assetMapper, fixture.newDomainService());
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private List<ListingAssetEntity> assetsOfType(String assetType) {
        return assetService.listAssets(11L, null).stream()
                .filter(asset -> assetType.equals(asset.getAssetType()))
                .toList();
    }

    @Test
    void replaceAssetsReplacesTypeRowsInOrderWithActor() {
        assetService.replaceAssets(11L, List.of(
                new ListingAssetUpsertItem("IMAGE", "old-a", "BUYER", 0, "ACTIVE"),
                new ListingAssetUpsertItem("IMAGE", "old-b", "BUYER", 1, "ACTIVE")), Set.of("IMAGE"), 123L);
        assertEquals(2, assetsOfType("IMAGE").size());

        assetService.replaceAssets(11L, List.of(
                new ListingAssetUpsertItem("IMAGE", "new-a", "BUYER", null, null),
                new ListingAssetUpsertItem("IMAGE", "new-b", "BUYER", null, null),
                new ListingAssetUpsertItem("IMAGE", "new-c", "BUYER", null, null),
                new ListingAssetUpsertItem("VIDEO", "video-a", "BUYER", null, "ACTIVE")), Set.of("IMAGE", "VIDEO"), 123L);

        List<ListingAssetEntity> images = assetsOfType("IMAGE");
        assertEquals(List.of("new-a", "new-b", "new-c"),
                images.stream().map(ListingAssetEntity::getStorageKey).toList());
        assertEquals(List.of(0, 1, 2), images.stream().map(ListingAssetEntity::getSortOrder).toList());
        assertTrue(images.stream().allMatch(asset -> "BUYER".equals(asset.getVisibility())
                && "ACTIVE".equals(asset.getStatus()) && Long.valueOf(123L).equals(asset.getCreatedBy())));
        assertEquals(List.of("video-a"), assetsOfType("VIDEO").stream()
                .map(ListingAssetEntity::getStorageKey).toList());
        // 旧 storageKey 已被替换语义移除（逻辑删除，非物理删除）。
        assertEquals(0, jdbcCount("SELECT COUNT(*) FROM listing_asset WHERE listing_id=11 AND storage_key IN ('old-a','old-b') AND deleted=0"));
        assertEquals(2, jdbcCount("SELECT COUNT(*) FROM listing_asset WHERE listing_id=11 AND storage_key IN ('old-a','old-b') AND deleted=1"));
    }

    @Test
    void replaceAssetsClearsTypeWhenItemListEmpty() {
        assetService.replaceAssets(11L, List.of(
                new ListingAssetUpsertItem("IMAGE", "a", "BUYER", 0, "ACTIVE")), Set.of("IMAGE"), 123L);
        assetService.replaceAssets(11L, List.of(), Set.of("IMAGE"), 123L);
        assertEquals(0, assetsOfType("IMAGE").size());
    }

    @Test
    void replaceAssetsKeepsUntouchedTypes() {
        assetService.replaceAssets(11L, List.of(
                new ListingAssetUpsertItem("IMAGE", "a", "BUYER", 0, "ACTIVE"),
                new ListingAssetUpsertItem("CERTIFICATE", "cert", "SENSITIVE", 0, "PENDING_REVIEW")),
                Set.of("IMAGE", "CERTIFICATE"), 123L);
        assetService.replaceAssets(11L, List.of(
                new ListingAssetUpsertItem("IMAGE", "b", "BUYER", 0, "ACTIVE")), Set.of("IMAGE"), 123L);
        assertEquals(List.of("b"), assetsOfType("IMAGE").stream().map(ListingAssetEntity::getStorageKey).toList());
        assertEquals(List.of("cert"), assetsOfType("CERTIFICATE").stream().map(ListingAssetEntity::getStorageKey).toList());
    }

    @Test
    void replaceAssetsRejectsInvalidValues() {
        assertThrows(RagException.class, () -> assetService.replaceAssets(11L,
                List.of(new ListingAssetUpsertItem("AUDIO", "a", "BUYER", 0, "ACTIVE")), Set.of("AUDIO"), 123L));
        assertThrows(RagException.class, () -> assetService.replaceAssets(11L,
                List.of(new ListingAssetUpsertItem("IMAGE", "a", "PUBLIC", 0, "ACTIVE")), Set.of("IMAGE"), 123L));
        assertThrows(RagException.class, () -> assetService.replaceAssets(11L,
                List.of(new ListingAssetUpsertItem("IMAGE", "  ", "BUYER", 0, "ACTIVE")), Set.of("IMAGE"), 123L));
        assertThrows(RagException.class, () -> assetService.replaceAssets(11L,
                List.of(new ListingAssetUpsertItem("IMAGE", "a", "BUYER", 0, "UNKNOWN")), Set.of("IMAGE"), 123L));
        assertEquals(0, jdbcCount("SELECT COUNT(*) FROM listing_asset WHERE listing_id=11"));
    }

    @Test
    void listAssetsFiltersVisibility() {
        assetService.replaceAssets(11L, List.of(
                new ListingAssetUpsertItem("IMAGE", "buyer-img", "BUYER", 0, "ACTIVE"),
                new ListingAssetUpsertItem("IMAGE", "internal-img", "INTERNAL", 0, "ACTIVE"),
                new ListingAssetUpsertItem("CERTIFICATE", "cert", "SENSITIVE", 0, "ACTIVE")),
                Set.of("IMAGE", "CERTIFICATE"), 123L);
        assertEquals(3, assetService.listAssets(11L, null).size());
        List<ListingAssetEntity> buyerVisible = assetService.listAssets(11L, "BUYER");
        assertEquals(1, buyerVisible.size());
        assertEquals("buyer-img", buyerVisible.get(0).getStorageKey());
    }

    @Test
    void retireAssetLogicallyDeletesSingleRow() {
        assetService.replaceAssets(11L, List.of(
                new ListingAssetUpsertItem("IMAGE", "a", "BUYER", 0, "ACTIVE"),
                new ListingAssetUpsertItem("IMAGE", "b", "BUYER", 1, "ACTIVE")), Set.of("IMAGE"), 123L);
        List<ListingAssetEntity> rows = assetService.listAssets(11L, null);
        assertEquals(2, rows.size());
        assetService.retireAsset(rows.get(0).getId());
        List<ListingAssetEntity> remaining = assetService.listAssets(11L, null);
        assertEquals(1, remaining.size());
        assertEquals("b", remaining.get(0).getStorageKey());
        // 逻辑删除：行保留、deleted=1。
        assertEquals(1, jdbcCount("SELECT COUNT(*) FROM listing_asset WHERE id=" + rows.get(0).getId() + " AND deleted=1"));
        assertNull(assetService.getAsset(rows.get(0).getId()));
    }

    @Test
    void registerLegacyAssetsIsIdempotentAndClassifiesVisibility() {
        fixture.jdbc.update("UPDATE listing_info SET image_urls='img-1,img-2', floor_plan_urls='plan-1', "
                + "video_urls='video-1', property_certificate_url='cert-1', contract_url='contract-1' WHERE id=11");
        assertEquals(6, assetService.registerLegacyAssets(11L, 123L));
        assertEquals(0, assetService.registerLegacyAssets(11L, 123L));

        List<ListingAssetEntity> rows = assetService.listAssets(11L, null);
        assertEquals(6, rows.size());
        assertTrue(rows.stream().allMatch(row -> "PENDING_REVIEW".equals(row.getStatus())
                && Long.valueOf(123L).equals(row.getCreatedBy())));
        assertEquals(List.of("img-1", "img-2"), assetsOfType("IMAGE").stream()
                .map(ListingAssetEntity::getStorageKey).toList());
        assertTrue(assetsOfType("IMAGE").stream().allMatch(row -> "BUYER".equals(row.getVisibility())));
        assertTrue(assetsOfType("FLOOR_PLAN").stream().allMatch(row -> "BUYER".equals(row.getVisibility())));
        assertTrue(assetsOfType("VIDEO").stream().allMatch(row -> "BUYER".equals(row.getVisibility())));
        assertTrue(assetsOfType("CERTIFICATE").stream().allMatch(row -> "SENSITIVE".equals(row.getVisibility())));
        assertTrue(assetsOfType("CONTRACT").stream().allMatch(row -> "SENSITIVE".equals(row.getVisibility())));
        // 旧字段保持原值（兼容读取，迁移不改写）。
        assertEquals("img-1,img-2", fixture.jdbc.queryForObject(
                "SELECT image_urls FROM listing_info WHERE id=11", String.class));

        // 已退役（逻辑删除）的行重新登记时按不存在处理，可再次登记。
        List<ListingAssetEntity> images = assetsOfType("IMAGE");
        assetService.retireAsset(images.get(0).getId());
        assertEquals(1, assetService.registerLegacyAssets(11L, 123L));
    }

    @Test
    void compatBindAssetsEndpointDelegatesToAssetRowsAndKeepsLegacyFields() {
        ListingRagOutbox outbox = mock(ListingRagOutbox.class);
        ListingRagIdentity identity = mock(ListingRagIdentity.class);
        when(identity.requireReader(any())).thenReturn(new AuthRagPrincipal(7L, "ADMIN", true, true, Long.MAX_VALUE));
        ListingManagementServiceImpl management = new ListingManagementServiceImpl(
                fixture.newDomainService(), new ListingConverter(), mock(ListingSearchService.class),
                new ListingKnowledgeDocumentAssembler(), outbox, identity,
                mock(ListingPriceRecordMapper.class), mock(ListingStatusRecordMapper.class),
                mock(ListingCommandReceiptService.class), mock(CustomerProfileVerifyClient.class),
                assetService, mock(ListingPrivateInfoService.class));
        try (var ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.assets.bind.own"), List.of("listing.assets.bind.own"), java.util.Map.of("BROKER", 9L))) {
            ListingDetailResponse response = management.bindAssets(11L,
                    new ListingAssetBindRequest(List.of("img-1", "img-2"), List.of("plan-1"), List.of()));
            assertEquals(List.of("img-1", "img-2"), response.imageUrls());
            assertEquals(List.of("plan-1"), response.floorPlanUrls());
            assertEquals(List.of(), response.videoUrls());
        }
        // listing_asset 行：IMAGE/FLOOR_PLAN/VIDEO → BUYER、ACTIVE、按列表顺序。
        assertEquals(List.of("img-1", "img-2"), assetsOfType("IMAGE").stream()
                .map(ListingAssetEntity::getStorageKey).toList());
        assertEquals(List.of(0, 1), assetsOfType("IMAGE").stream()
                .map(ListingAssetEntity::getSortOrder).toList());
        assertEquals(List.of("plan-1"), assetsOfType("FLOOR_PLAN").stream()
                .map(ListingAssetEntity::getStorageKey).toList());
        // 旧字段兼容双写（过渡期）。
        assertEquals("img-1,img-2", fixture.jdbc.queryForObject(
                "SELECT image_urls FROM listing_info WHERE id=11", String.class));
        assertEquals("plan-1", fixture.jdbc.queryForObject(
                "SELECT floor_plan_urls FROM listing_info WHERE id=11", String.class));
        assertNull(fixture.jdbc.queryForObject(
                "SELECT video_urls FROM listing_info WHERE id=11", String.class));
        verify(outbox).publishCurrent(11L);
    }

    private long jdbcCount(String sql) {
        return fixture.jdbc.queryForObject(sql, Long.class);
    }
}
