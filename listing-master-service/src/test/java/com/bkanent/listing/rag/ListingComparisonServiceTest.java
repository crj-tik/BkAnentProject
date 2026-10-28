package com.bkanent.listing.rag;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
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
import com.bkanent.listing.comparison.ListingComparisonException;
import com.bkanent.listing.comparison.ListingComparisonPdfService;
import com.bkanent.listing.comparison.ListingComparisonReportStore;
import com.bkanent.listing.comparison.ListingComparisonService;
import com.bkanent.listing.comparison.config.ListingComparisonProperties;
import com.bkanent.listing.source.ListingBusinessSourceService;
import com.bkanent.listing.source.FixtureListingSourceAdapter;
import com.bkanent.listing.source.ListingSourceProperties;
import com.bkanent.listing.source.ListingSourceAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ListingComparisonServiceTest {
    private static final List<String> ALL_OPERATIONS = List.of(
            "listing.read", "compare.report.create", "compare.report.read.own");
    private static final List<ListingSourceSnapshot> SNAPSHOTS = List.of(
            snapshot("legacy-local", "101", "rev-101", ListingDataMode.UNKNOWN, "3200000", "100"),
            snapshot("fixture", "listing-001", "fixture-rev-1", ListingDataMode.SIMULATED, "3650000", null));

    @TempDir Path directory;
    private ListingSourceAdapter source;
    private ListingComparisonService service;
    private AtomicReference<ListingSourceStatus> sourceStatus;

    @BeforeEach
    void setUp() {
        source = mock(ListingSourceAdapter.class);
        sourceStatus = new AtomicReference<>(ListingSourceStatus.CURRENT);
        when(source.batchRead(any(), any())).thenAnswer(invocation -> {
            var request = (com.bkanent.common.listing.ListingSnapshotBatchReadRequest) invocation.getArgument(0);
            return request.refs().stream().map(ref -> {
                ListingSourceSnapshot snapshot = find(ref.listingRef());
                if (snapshot == null) return new ListingSnapshotBatchReadItem(ref.listingRef(),
                        ListingSourceStatus.NOT_ACCESSIBLE, null);
                return new ListingSnapshotBatchReadItem(ref.listingRef(), ListingSourceStatus.CURRENT, snapshot);
            }).toList();
        });
        when(source.check(any(), any())).thenAnswer(invocation -> {
            var request = (com.bkanent.common.listing.ListingSnapshotCheckRequest) invocation.getArgument(0);
            return request.refs().stream().map(ref -> new ListingSnapshotCheckItem(ref.listingRef(),
                    ref.expectedRevision(), sourceStatus.get())).toList();
        });
        var sourceService = new ListingBusinessSourceService(source);
        var properties = new ListingComparisonProperties();
        properties.setStorageDir(directory.toString());
        var store = new ListingComparisonReportStore(new ObjectMapper().findAndRegisterModules(), properties,
                new ListingComparisonPdfService());
        store.initialize();
        service = new ListingComparisonService(sourceService, store);
    }

    @Test
    void createsAndCachesVersionBoundComparisonWithUnknownModeAndYuanUnitPrice() throws IOException {
        try (AuthzRequestScope.Scope ignored = scope(ALL_OPERATIONS)) {
            var first = service.create(request());
            var second = service.create(request());

            assertEquals(first.reportId(), second.reportId());
            assertEquals("domain-parallel-v1", first.contractVersion());
            assertEquals("listing-comparison-v1", first.rulesVersion());
            assertEquals(ListingDataMode.UNKNOWN, first.dataMode());
            assertEquals("32000.00", first.rows().get(0).values().get("unitPriceCnyPerSquareMetre"));
            assertEquals("未知", first.rows().get(1).values().get("unitPriceCnyPerSquareMetre"));
            assertEquals("UNKNOWN", first.dataMode().name());
            assertEquals(2, first.sources().size());
            assertTrue(new String(service.loadPdf(first.reportId()).getContentAsByteArray(), StandardCharsets.ISO_8859_1)
                    .startsWith("%PDF-1.4"));
        }
        verify(source, times(2)).batchRead(any(), any());
        verify(source, times(3)).check(any(), any());
    }

    @Test
    void simulatedProviderCompletesSearchComparisonHistoryAndPdfWithoutLosingMode() throws IOException {
        ListingSourceProperties sourceProperties = new ListingSourceProperties();
        sourceProperties.setFixtureBrokerId(77L);
        var fixture = new FixtureListingSourceAdapter(sourceProperties);
        var business = new ListingBusinessSourceService(fixture);
        var reportProperties = new ListingComparisonProperties();
        reportProperties.setStorageDir(directory.resolve("simulated").toString());
        var reportStore = new ListingComparisonReportStore(new ObjectMapper().findAndRegisterModules(),
                reportProperties, new ListingComparisonPdfService());
        reportStore.initialize();
        var comparison = new ListingComparisonService(business, reportStore);

        try (AuthzRequestScope.Scope ignored = scope(ALL_OPERATIONS)) {
            var search = business.search(new com.bkanent.common.listing.ListingBusinessSearchRequest(
                    "domain-parallel-v1", "模拟房源", null, 10));
            assertEquals(3, search.items().size());
            assertTrue(search.items().stream().allMatch(item -> item.dataMode() == ListingDataMode.SIMULATED));
            var selected = search.items().subList(0, 2);
            var refs = selected.stream().map(item -> new ListingSourceVersionRef(item.listingRef(), item.revision())).toList();
            var snapshots = business.batchRead(new com.bkanent.common.listing.ListingSnapshotBatchReadRequest(
                    "domain-parallel-v1", refs)).items().stream().map(ListingSnapshotBatchReadItem::snapshot).toList();
            assertTrue(business.check(new com.bkanent.common.listing.ListingSnapshotCheckRequest(
                    "domain-parallel-v1", refs)).items().stream()
                    .allMatch(item -> item.status() == ListingSourceStatus.CURRENT));

            var request = new ListingComparisonCreateRequest("domain-parallel-v1", refs, true);
            var first = comparison.create(request);
            var retry = comparison.create(request);
            var history = comparison.getOwned(first.reportId());
            byte[] pdf = comparison.loadPdf(first.reportId()).getContentAsByteArray();

            assertEquals(first.reportId(), retry.reportId());
            assertEquals(ListingDataMode.SIMULATED, history.dataMode());
            assertTrue(history.sources().stream().allMatch(item -> item.dataMode() == ListingDataMode.SIMULATED));
            assertEquals(snapshots.stream().map(ListingSourceSnapshot::listingRef).toList(),
                    history.sources().stream().map(ListingSourceSnapshot::listingRef).toList());
            assertTrue(new String(pdf, StandardCharsets.ISO_8859_1).startsWith("%PDF-1.4"));
            assertTrue(pdf.length > 10_000);
        }
    }

    @Test
    void staleInaccessibleAndUnavailableSourcesBlockHistoricalBodyAndPdf() {
        String reportId;
        try (AuthzRequestScope.Scope ignored = scope(ALL_OPERATIONS)) {
            reportId = service.create(request()).reportId();
        }

        try (AuthzRequestScope.Scope ignored = scope(ALL_OPERATIONS)) {
            sourceStatus.set(ListingSourceStatus.STALE);
            assertEquals("SOURCE_VERSION_CONFLICT",
                    assertThrows(ListingComparisonException.class, () -> service.getOwned(reportId)).code());
            assertThrows(ListingComparisonException.class, () -> service.loadPdf(reportId));

            sourceStatus.set(ListingSourceStatus.NOT_ACCESSIBLE);
            assertEquals(AuthzErrorCode.PERMISSION_DENIED,
                    assertThrows(AuthzException.class, () -> service.getOwned(reportId)).code());

            sourceStatus.set(ListingSourceStatus.UNAVAILABLE);
            assertEquals("DEPENDENCY_UNAVAILABLE",
                    assertThrows(ListingComparisonException.class, () -> service.getOwned(reportId)).code());
        }
    }

    @Test
    void createAndReadOperationsRemainIndependentAndFailBeforeSourceAccess() {
        try (AuthzRequestScope.Scope ignored = scope(List.of("listing.read", "compare.report.read.own"))) {
            assertEquals(AuthzErrorCode.PERMISSION_DENIED,
                    assertThrows(AuthzException.class, () -> service.create(request())).code());
        }
        verifyNoInteractions(source);
    }

    @Test
    void rejectsTwoToSixStableReferencesAndStaleExpectedVersions() {
        try (AuthzRequestScope.Scope ignored = scope(ALL_OPERATIONS)) {
            assertThrows(ListingComparisonException.class, () -> service.create(new ListingComparisonCreateRequest(
                    "domain-parallel-v1", List.of(request().refs().get(0)), true)));
            sourceStatus.set(ListingSourceStatus.STALE);
            assertEquals("SOURCE_VERSION_CONFLICT",
                    assertThrows(ListingComparisonException.class, () -> service.create(request())).code());
        }
    }

    private AuthzRequestScope.Scope scope(List<String> operations) {
        return ListingAuthzScopeFixture.open(false, operations, operations, Map.of("BROKER", 77L));
    }

    private ListingComparisonCreateRequest request() {
        return new ListingComparisonCreateRequest("domain-parallel-v1", SNAPSHOTS.stream()
                .map(snapshot -> new ListingSourceVersionRef(snapshot.listingRef(), snapshot.revision())).toList(), true);
    }

    private ListingSourceSnapshot find(ListingRef ref) {
        return SNAPSHOTS.stream().filter(snapshot -> snapshot.listingRef().equals(ref)).findFirst().orElse(null);
    }

    private static ListingSourceSnapshot snapshot(String system, String id, String revision, ListingDataMode mode,
                                                   String price, String area) {
        return ListingSnapshotFactory.create(new ListingRef(system, id), revision,
                Instant.parse("2026-10-27T00:00:00Z"), mode, system + "-dataset",
                new ListingSnapshotFacts("测试房源 " + id, area,
                        new ListingMoney(price, "CNY", ListingMoney.Unit.YUAN), Map.of(), List.of()));
    }
}
