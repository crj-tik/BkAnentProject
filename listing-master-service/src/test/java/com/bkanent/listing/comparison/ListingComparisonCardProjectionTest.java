package com.bkanent.listing.comparison;

import com.bkanent.common.authz.AuthzRequestScope;
import com.bkanent.common.listing.ListingComparisonCreateRequest;
import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingMoney;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSnapshotFactory;
import com.bkanent.common.listing.ListingSnapshotFacts;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.listing.ListingSourceStatus;
import com.bkanent.common.listing.ListingSourceVersionRef;
import com.bkanent.listing.comparison.config.ListingComparisonProperties;
import com.bkanent.listing.comparison.model.ListingComparisonCard;
import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;
import com.bkanent.listing.comparison.model.ListingComparisonStoredReport;
import com.bkanent.listing.rag.ListingAuthzScopeFixture;
import com.bkanent.listing.source.ListingBusinessSourceService;
import com.bkanent.listing.source.ListingSourceAdapter;
import com.bkanent.listing.source.ListingSourceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务 2.2：卡片投影测试——确定性投影、卡片与行同源一致、未知不补值、
 * 单价缺省、徽标仅来自确定性指标、存量旧报告读取自动获得卡片。
 */
class ListingComparisonCardProjectionTest {
    private static final List<String> ALL_OPERATIONS = List.of(
            "listing.read", "compare.report.create", "compare.report.read.own");
    private static final List<ListingSourceSnapshot> SNAPSHOTS = List.of(
            snapshot("101", "3200000", "100", List.of()),
            snapshot("102", "3100000", "98", List.of()),
            snapshot("103", "3650000", null, List.of("schoolZone")));

    @TempDir Path directory;
    private ListingComparisonService service;
    private ListingBusinessSourceService business;
    private ListingComparisonProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        ListingSourceAdapter source = mock(ListingSourceAdapter.class);
        when(source.batchRead(any(), any())).thenAnswer(invocation -> {
            var request = (com.bkanent.common.listing.ListingSnapshotBatchReadRequest) invocation.getArgument(0);
            return request.refs().stream().map(ref -> {
                ListingSourceSnapshot snapshot = SNAPSHOTS.stream()
                        .filter(item -> item.listingRef().equals(ref.listingRef())).findFirst().orElse(null);
                return snapshot == null
                        ? new ListingSnapshotBatchReadItem(ref.listingRef(), ListingSourceStatus.NOT_ACCESSIBLE, null)
                        : new ListingSnapshotBatchReadItem(ref.listingRef(), ListingSourceStatus.CURRENT, snapshot);
            }).toList();
        });
        when(source.check(any(), any())).thenAnswer(invocation -> {
            var request = (com.bkanent.common.listing.ListingSnapshotCheckRequest) invocation.getArgument(0);
            return request.refs().stream().map(ref -> new ListingSnapshotCheckItem(ref.listingRef(),
                    ref.expectedRevision(), ListingSourceStatus.CURRENT)).toList();
        });
        business = new ListingBusinessSourceService(source);
        properties = new ListingComparisonProperties();
        properties.setStorageDir(directory.toString());
        var store = new ListingComparisonReportStore(mapper, properties, new ListingComparisonPdfService());
        store.initialize();
        service = new ListingComparisonService(business, store);
    }

    @Test
    void fillProjectsCardsAndDeterministicWinners() {
        ListingComparisonReportResponse legacy = legacyResponse("unit-report", SNAPSHOTS);
        assertTrue(legacy.cards().isEmpty(), "11 参构造的报告不携带卡片投影");

        ListingComparisonReportResponse filled = ListingComparisonCardProjection.fill(legacy);

        assertEquals(3, filled.cards().size());
        ListingComparisonCard cardA = card(filled, "101");
        ListingComparisonCard cardB = card(filled, "102");
        ListingComparisonCard cardC = card(filled, "103");

        // 事实字段：显式单位与原样属性
        assertEquals("3200000", cardA.totalPrice().amount());
        assertEquals("CNY", cardA.totalPrice().currency());
        assertEquals(ListingMoney.Unit.YUAN, cardA.totalPrice().unit());
        assertEquals("100", cardA.areaSquareMetres());
        assertEquals("32000.00", cardA.unitPriceCnyPerSquareMetre());
        assertEquals("2室1厅", cardA.attributes().get("layout"));
        assertEquals("ON_SALE", cardA.attributes().get("status"));

        // 未知不补值：C 缺面积与学区
        assertNull(cardC.unitPriceCnyPerSquareMetre(), "缺面积的房源单价不可计算");
        assertTrue(cardC.unknownFields().contains("schoolZone"), "未知字段显式列出");
        assertFalse(cardC.attributes().containsKey("schoolZone"), "未知字段不提供值");

        // 徽标仅来自确定性指标
        assertEquals(List.of("highestUnitPriceCnyPerSquareMetre", "largestAreaSquareMetres"), cardA.highlights());
        assertEquals(List.of("lowestTotalPriceCny", "lowestUnitPriceCnyPerSquareMetre"), cardB.highlights());
        assertEquals(List.of("highestTotalPriceCny"), cardC.highlights());

        // 指标归属与值
        assertEquals(5, filled.metricWinners().size());
        var winner = filled.metricWinners().stream()
                .filter(item -> "lowestTotalPriceCny".equals(item.metric())).findFirst().orElseThrow();
        assertEquals(new ListingRef("legacy-local", "102"), winner.listingRef());
        assertEquals("3100000", winner.value());
        assertTrue(filled.metricWinners().stream().allMatch(item -> List.of(
                "lowestTotalPriceCny", "highestTotalPriceCny",
                "lowestUnitPriceCnyPerSquareMetre", "highestUnitPriceCnyPerSquareMetre",
                "largestAreaSquareMetres").contains(item.metric())), "指标键只能是既有确定性指标");
    }

    @Test
    void cardValuesMatchRowsProjection() {
        try (AuthzRequestScope.Scope ignored = scope()) {
            ListingComparisonReportResponse filled = service.create(request());
            for (ListingComparisonCard card : filled.cards()) {
                var row = filled.rows().stream()
                        .filter(item -> item.listingRef().equals(card.listingRef())).findFirst().orElseThrow();
                if (card.totalPrice() == null) {
                    assertEquals("未知", row.values().get("totalPriceCny"));
                } else {
                    assertEquals(card.totalPrice().amount(), row.values().get("totalPriceCny"));
                }
                if (card.areaSquareMetres() == null) {
                    assertEquals("未知", row.values().get("areaSquareMetres"));
                } else {
                    assertEquals(card.areaSquareMetres(), row.values().get("areaSquareMetres"));
                }
                if (card.unitPriceCnyPerSquareMetre() == null) {
                    assertEquals("未知", row.values().get("unitPriceCnyPerSquareMetre"));
                } else {
                    assertEquals(card.unitPriceCnyPerSquareMetre(), row.values().get("unitPriceCnyPerSquareMetre"));
                }
            }
        }
    }

    @Test
    void serviceReturnsCardsOnCreateAndRead() {
        try (AuthzRequestScope.Scope ignored = scope()) {
            ListingComparisonReportResponse created = service.create(request());
            assertEquals(3, created.cards().size());
            assertEquals(SNAPSHOTS.stream().map(ListingSourceSnapshot::listingRef).toList(),
                    created.cards().stream().map(ListingComparisonCard::listingRef).toList());

            ListingComparisonReportResponse read = service.getOwned(created.reportId());
            assertEquals(3, read.cards().size());
            assertEquals(created.cards().stream().map(ListingComparisonCard::listingRef).toList(),
                    read.cards().stream().map(ListingComparisonCard::listingRef).toList());
            assertEquals(created.metricWinners(), read.metricWinners());
        }
    }

    @Test
    void legacyStoredFileWithoutCardFieldsGetsProjectionOnRead() throws Exception {
        // 模拟存量旧报告：11 参构造（无卡片字段）写入存储文件，并从 JSON 中移除卡片字段
        String legacyReportId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
        List<ListingSourceSnapshot> sources = List.of(SNAPSHOTS.get(0), SNAPSHOTS.get(1));
        ListingComparisonReportResponse legacy = legacyResponse(legacyReportId, sources);
        String fingerprint = ListingComparisonFingerprint.sourceVersionHash(sources);
        var stored = new ListingComparisonStoredReport("123", fingerprint, fingerprint, legacy, Instant.now());
        Path file = directory.resolve(legacyReportId + ".listing.json");
        mapper.writeValue(file.toFile(), stored);
        ObjectNode tree = (ObjectNode) mapper.readTree(file.toFile());
        ((ObjectNode) tree.get("report")).remove("cards");
        ((ObjectNode) tree.get("report")).remove("metricWinners");
        mapper.writeValue(file.toFile(), tree);

        var reloadedStore = new ListingComparisonReportStore(mapper, properties, new ListingComparisonPdfService());
        reloadedStore.initialize();
        var reloadedService = new ListingComparisonService(business, reloadedStore);

        try (AuthzRequestScope.Scope ignored = scope()) {
            ListingComparisonReportResponse read = reloadedService.getOwned(legacyReportId);
            assertEquals(2, read.cards().size());
            var lowest = read.metricWinners().stream()
                    .filter(item -> "lowestTotalPriceCny".equals(item.metric())).findFirst().orElseThrow();
            assertEquals(new ListingRef("legacy-local", "102"), lowest.listingRef());
            assertTrue(read.cards().stream().anyMatch(card -> "102".equals(card.listingRef().sourceId())
                    && card.highlights().contains("lowestTotalPriceCny")));
        }
    }

    private ListingComparisonCard card(ListingComparisonReportResponse report, String sourceId) {
        return report.cards().stream()
                .filter(item -> sourceId.equals(item.listingRef().sourceId())).findFirst().orElseThrow();
    }

    private AuthzRequestScope.Scope scope() {
        return ListingAuthzScopeFixture.open(false, ALL_OPERATIONS, ALL_OPERATIONS, Map.of("BROKER", 77L));
    }

    private ListingComparisonCreateRequest request() {
        return new ListingComparisonCreateRequest("domain-parallel-v1", SNAPSHOTS.stream()
                .map(snapshot -> new ListingSourceVersionRef(snapshot.listingRef(), snapshot.revision())).toList(), true);
    }

    /** 11 参构造的存量格式响应（无卡片投影），用于投影单元测试与旧文件模拟。 */
    private ListingComparisonReportResponse legacyResponse(String reportId, List<ListingSourceSnapshot> sources) {
        return new ListingComparisonReportResponse(reportId, "domain-parallel-v1", "listing-comparison-v1",
                Instant.now(), ListingDataMode.UNKNOWN, sources, List.of(), List.of(),
                "| 表格 |", "结论", "/listings/comparisons/" + reportId + "/pdf");
    }

    private static ListingSourceSnapshot snapshot(String id, String price, String area, List<String> unknownFields) {
        return ListingSnapshotFactory.create(new ListingRef("legacy-local", id), "rev-" + id,
                Instant.parse("2026-10-27T00:00:00Z"), ListingDataMode.UNKNOWN, "legacy-local-dataset",
                new ListingSnapshotFacts("测试房源 " + id, area,
                        new ListingMoney(price, "CNY", ListingMoney.Unit.YUAN),
                        Map.of("layout", "2室1厅", "status", "ON_SALE"), unknownFields));
    }
}
