package com.bkanent.listing.service;

import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.rag.AuthRagPrincipal;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.customer.CustomerProfileVerifyClient;
import com.bkanent.listing.mapper.ListingPriceRecordMapper;
import com.bkanent.listing.mapper.ListingStatusRecordMapper;
import com.bkanent.listing.model.ListingAssetUpsertItem;
import com.bkanent.listing.model.ListingAssetView;
import com.bkanent.listing.model.ListingBuyerSearchQuery;
import com.bkanent.listing.model.ListingBuyerSearchResponse;
import com.bkanent.listing.model.ListingBuyerViewResponse;
import com.bkanent.listing.model.ListingQueryRequest;
import com.bkanent.listing.rag.ListingAuthzScopeFixture;
import com.bkanent.listing.rag.ListingRagIdentity;
import com.bkanent.listing.rag.ListingRagOutbox;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import com.bkanent.listing.service.impl.ListingAssetServiceImpl;
import com.bkanent.listing.service.impl.ListingManagementServiceImpl;
import com.bkanent.listing.service.impl.ListingPrivateInfoServiceImpl;
import com.bkanent.listing.config.ListingPrivateInfoProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 4.1 买方可见搜索与详情：游标分页稳定性、过滤条件、非在售/已删不返回、投影无敏感、素材仅 BUYER、查询无副作用。 */
class ListingBuyerSearchTest {

    private ListingMybatisH2Fixture fixture;
    private ListingManagementServiceImpl management;
    private ListingRagOutbox outbox;
    private ListingAssetServiceImpl assets;

    @BeforeEach
    void setup() {
        fixture = new ListingMybatisH2Fixture();
        outbox = mock(ListingRagOutbox.class);
        ListingRagIdentity identity = mock(ListingRagIdentity.class);
        when(identity.requireReader(any())).thenReturn(new AuthRagPrincipal(7L, "ADMIN", true, true, Long.MAX_VALUE));
        assets = new ListingAssetServiceImpl(fixture.assetMapper, fixture.newDomainService());
        ListingPrivateInfoProperties properties = new ListingPrivateInfoProperties();
        management = new ListingManagementServiceImpl(fixture.newDomainService(), new ListingConverter(),
                mock(ListingSearchService.class), new ListingKnowledgeDocumentAssembler(), outbox, identity,
                mock(ListingPriceRecordMapper.class), mock(ListingStatusRecordMapper.class),
                mock(ListingCommandReceiptService.class), mock(CustomerProfileVerifyClient.class),
                assets, new ListingPrivateInfoServiceImpl(fixture.privateInfoMapper, fixture.newDomainService(), properties));
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private void insertOnSale(long id, String updatedAt, String districtCode, String communityName,
                              String layout, BigDecimal area, BigDecimal askingYuan, BigDecimal totalWan) {
        fixture.insertListing(id, 9L, "ON_SALE", updatedAt, area, askingYuan, totalWan,
                districtCode, communityName, layout, 0);
    }

    private ListingBuyerSearchResponse search(ListingBuyerSearchQuery query) {
        try (var ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.read"), List.of("listing.read"), Map.of("BROKER", 9L))) {
            return management.searchBuyerListings(query);
        }
    }

    @Test
    void cursorPaginationIsStableWithoutOverlapOrGap() {
        // 25 套在售房源，每 5 套共享同一 updated_at（校验 (updated_at,id) 键集分页与并列打破）。
        for (long id = 1; id <= 25; id++) {
            long minuteGroup = (id - 1) / 5;
            insertOnSale(id, String.format("2026-11-05 10:%02d:00", minuteGroup),
                    null, null, "两室", BigDecimal.TEN, BigDecimal.valueOf(100000L), BigDecimal.TEN);
        }
        List<Long> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            ListingBuyerSearchResponse page = search(new ListingBuyerSearchQuery(
                    null, null, null, null, null, null, null, cursor, 10));
            assertTrue(page.items().size() <= 10);
            page.items().forEach(item -> seen.add(item.id()));
            pages++;
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
            assertTrue(pages <= 5, "分页未收敛");
        }
        // 期望顺序：updated_at DESC, id DESC → 25..1，不重不漏。
        List<Long> expected = new ArrayList<>();
        for (long id = 25; id >= 1; id--) {
            expected.add(id);
        }
        assertEquals(expected, seen);
        assertEquals(3, pages);

        // 默认 limit=20：25 行返回 20 条并带下一页游标。
        ListingBuyerSearchResponse defaultPage = search(new ListingBuyerSearchQuery(
                null, null, null, null, null, null, null, null, null));
        assertEquals(20, defaultPage.items().size());
        assertNotNull(defaultPage.nextCursor());
    }

    @Test
    void invalidCursorIsRejected() {
        List<String> invalid = List.of(
                "!!!not-base64!!!",
                java.util.Base64.getUrlEncoder().encodeToString("no-separator".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.util.Base64.getUrlEncoder().encodeToString("x|y".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                java.util.Base64.getUrlEncoder().encodeToString("2026-11-05T10:00:00|not-a-number".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (String cursor : invalid) {
            assertEquals(RagErrorCode.INVALID_ARGUMENT,
                    assertThrows(RagException.class, () -> search(new ListingBuyerSearchQuery(
                            null, null, null, null, null, null, null, cursor, 10))).code());
        }
    }

    @Test
    void filtersApplyAcrossDistrictCommunityLayoutAreaAndPrice() {
        insertOnSale(1, "2026-11-05 10:00:00", "310000", "阳光花园", "两室",
                new BigDecimal("80.00"), new BigDecimal("800000.00"), null);
        insertOnSale(2, "2026-11-05 10:01:00", "310000", "翠湖天地", "三室",
                new BigDecimal("120.00"), new BigDecimal("2000000.00"), null);
        // asking 为空的存量行：价格过滤回退 total_price×10000（100 万 × 10000）。
        insertOnSale(3, "2026-11-05 10:02:00", "440000", "阳光里", "两室",
                new BigDecimal("90.00"), null, new BigDecimal("100.00"));
        fixture.insertListing(4, 9L, "ON_SALE", "2026-11-05 10:03:00", new BigDecimal("60.00"),
                new BigDecimal("500000.00"), null, "310000", "阳光花园", "一室", 0);

        // 结果按 updated_at DESC, id DESC 排序。
        assertEquals(List.of(4L, 2L, 1L), ids(search(new ListingBuyerSearchQuery(
                "310000", null, null, null, null, null, null, null, null))));
        assertEquals(List.of(4L, 3L, 1L), ids(search(new ListingBuyerSearchQuery(
                null, "阳光", null, null, null, null, null, null, null))));
        assertEquals(List.of(3L, 1L), ids(search(new ListingBuyerSearchQuery(
                null, null, "两室", null, null, null, null, null, null))));
        assertEquals(List.of(3L, 1L), ids(search(new ListingBuyerSearchQuery(
                null, null, null, new BigDecimal("70.00"), new BigDecimal("100.00"), null, null, null, null))));
        assertEquals(List.of(4L, 3L, 1L), ids(search(new ListingBuyerSearchQuery(
                null, null, null, null, null, new BigDecimal("500000.00"), new BigDecimal("1500000.00"), null, null))));
        assertEquals(List.of(2L), ids(search(new ListingBuyerSearchQuery(
                null, null, null, null, null, new BigDecimal("1500000.00"), null, null, null))));
    }

    @Test
    void privateListingQueryUsesExplicitUserOwnerAndOnlyFallsBackForNullOwners() {
        fixture.insertListing(31, 41L, "OFFLINE", "2026-11-05 10:00:00", BigDecimal.TEN,
                BigDecimal.valueOf(100000L), BigDecimal.TEN, null, null, "两室", 0);
        fixture.insertListing(32, 41L, "OFFLINE", "2026-11-05 10:01:00", BigDecimal.TEN,
                BigDecimal.valueOf(100000L), BigDecimal.TEN, null, null, "两室", 0);
        fixture.insertListing(33, 82L, "OFFLINE", "2026-11-05 10:02:00", BigDecimal.TEN,
                BigDecimal.valueOf(100000L), BigDecimal.TEN, null, null, "两室", 0);
        fixture.jdbc.update("UPDATE listing_info SET owner_user_id=123 WHERE id=31");
        fixture.jdbc.update("UPDATE listing_info SET owner_user_id=124 WHERE id=32");

        var rows = fixture.newDomainService().search(new ListingQueryRequest(
                null, null, 41L, null, null, null, null, null, 123L));

        assertEquals(List.of(31L), rows.stream().map(com.bkanent.listing.entity.ListingEntity::getId).toList());
        // A legacy row with no ownerUserId remains visible only through the matching trusted broker filter.
        fixture.jdbc.update("UPDATE listing_info SET owner_user_id=NULL WHERE id=31");
        rows = fixture.newDomainService().search(new ListingQueryRequest(
                null, null, 41L, null, null, null, null, null, 123L));
        assertEquals(List.of(31L), rows.stream().map(com.bkanent.listing.entity.ListingEntity::getId).toList());
    }

    @Test
    void nonSaleDeletedOrMissingListingsAreNotReturned() {
        insertOnSale(1, "2026-11-05 10:00:00", null, null, "两室", BigDecimal.TEN,
                BigDecimal.valueOf(100000L), BigDecimal.TEN);
        fixture.insertListing(2, 9L, "PENDING_REVIEW", "2026-11-05 10:01:00", BigDecimal.TEN,
                BigDecimal.valueOf(100000L), BigDecimal.TEN, null, null, "两室", 0);
        fixture.insertListing(3, 9L, "OFFLINE", "2026-11-05 10:02:00", BigDecimal.TEN,
                BigDecimal.valueOf(100000L), BigDecimal.TEN, null, null, "两室", 0);
        fixture.insertListing(4, 9L, "SOLD", "2026-11-05 10:03:00", BigDecimal.TEN,
                BigDecimal.valueOf(100000L), BigDecimal.TEN, null, null, "两室", 0);
        fixture.insertListing(5, 9L, "ON_SALE", "2026-11-05 10:04:00", BigDecimal.TEN,
                BigDecimal.valueOf(100000L), BigDecimal.TEN, null, null, "两室", 1);

        ListingBuyerSearchResponse result = search(new ListingBuyerSearchQuery(
                null, null, null, null, null, null, null, null, null));
        assertEquals(List.of(1L), ids(result));

        try (var ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.read"), List.of("listing.read"), Map.of("BROKER", 9L))) {
            for (long id : List.of(2L, 3L, 4L, 5L, 99L)) {
                assertNull(management.getBuyerDetail(id), "非在售/已删/不存在不应返回: " + id);
            }
            ListingBuyerViewResponse buyer = management.getBuyerDetail(1L);
            assertNotNull(buyer);
            assertEquals("ON_SALE", buyer.status());
        }
    }

    @Test
    void buyerDetailExposesOnlyBuyerActiveAssetsAndExplicitYuanPrice() throws Exception {
        insertOnSale(1, "2026-11-05 10:00:00", null, null, "两室",
                BigDecimal.TEN, null, new BigDecimal("100.00"));
        fixture.jdbc.update("UPDATE listing_info SET owner_name='业主甲', certificate_no='CERT-1', "
                + "property_certificate_url='cert-url', contract_url='contract-url' WHERE id=1");
        assets.replaceAssets(1L, List.of(
                new ListingAssetUpsertItem("IMAGE", "buyer-img", "BUYER", 0, "ACTIVE"),
                new ListingAssetUpsertItem("IMAGE", "pending-img", "BUYER", 1, "PENDING_REVIEW"),
                new ListingAssetUpsertItem("IMAGE", "internal-img", "INTERNAL", 2, "ACTIVE"),
                new ListingAssetUpsertItem("CERTIFICATE", "cert-url", "SENSITIVE", 0, "ACTIVE"),
                new ListingAssetUpsertItem("VIDEO", "buyer-video", "BUYER", 5, "ACTIVE")),
                Set.of("IMAGE", "CERTIFICATE", "VIDEO"), 123L);

        ListingBuyerViewResponse buyer;
        try (var ignored = ListingAuthzScopeFixture.open(false,
                List.of("listing.read"), List.of("listing.read"), Map.of("BROKER", 9L))) {
            buyer = management.getBuyerDetail(1L);
        }
        assertNotNull(buyer);
        // asking 为空的存量行回退 total_price×10000 = 100 万元（明确单位：元）。
        assertEquals(new BigDecimal("1000000.00"), buyer.askingPriceYuan());
        // 素材仅 BUYER 且 ACTIVE。
        List<ListingAssetView> views = buyer.assets();
        assertEquals(2, views.size());
        assertEquals("buyer-img", views.get(0).storageKey());
        assertEquals("buyer-video", views.get(1).storageKey());
        // 序列化投影不含敏感字段值。
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(buyer);
        assertFalse(body.contains("业主甲"));
        assertFalse(body.contains("CERT-1"));
        assertFalse(body.contains("cert-url"));
        assertFalse(body.contains("contract-url"));
        assertFalse(body.contains("pending-img"));
        assertFalse(body.contains("internal-img"));
    }

    @Test
    void searchRequiresReadPermissionAndCreatesNoSideEffects() {
        insertOnSale(1, "2026-11-05 10:00:00", null, null, "两室", BigDecimal.TEN,
                BigDecimal.valueOf(100000L), BigDecimal.TEN);
        assets.replaceAssets(1L, List.of(new ListingAssetUpsertItem("IMAGE", "img", "BUYER", 0, "ACTIVE")),
                Set.of("IMAGE"), 123L);
        List<Map<String, Object>> before = fixture.jdbc.queryForList(
                "SELECT id, updated_at, deleted, status FROM listing_info ORDER BY id");

        // 无 listing.read 权限：拒绝。
        try (var ignored = ListingAuthzScopeFixture.open(false, List.of(), List.of(), Map.of("BROKER", 9L))) {
            assertThrows(AuthzException.class, () -> management.searchBuyerListings(
                    new ListingBuyerSearchQuery(null, null, null, null, null, null, null, null, null)));
            assertThrows(AuthzException.class, () -> management.getBuyerDetail(1L));
        }

        ListingBuyerSearchResponse result = search(new ListingBuyerSearchQuery(
                null, null, null, null, null, null, null, null, null));
        assertEquals(List.of(1L), ids(result));
        // 查询无副作用：数据、素材行与 outbox 均不变（浏览不代表咨询，不创建任何档案）。
        assertEquals(before, fixture.jdbc.queryForList(
                "SELECT id, updated_at, deleted, status FROM listing_info ORDER BY id"));
        assertEquals(1, fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM listing_asset WHERE listing_id=1 AND deleted=0", Long.class).longValue());
        assertEquals(0, fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM listing_private_info", Long.class).longValue());
        verifyNoInteractions(outbox);
    }

    private static List<Long> ids(ListingBuyerSearchResponse page) {
        return page.items().stream().map(ListingBuyerViewResponse::id).toList();
    }
}
