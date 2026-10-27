package com.bkanent.listing.source;

import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;
import com.bkanent.common.listing.ListingSnapshotCheckRequest;
import com.bkanent.common.listing.ListingSourceStatus;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ListingSourceAdapterTest {
    private HttpServer server;
    private final AtomicReference<Map<String, String>> requestHeaders = new AtomicReference<>(Map.of());
    private final AtomicReference<String> requestPath = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void fixtureIsFixedAndMarkedSimulated() {
        ListingSourceProperties properties = new ListingSourceProperties();
        properties.setFixtureBrokerId(9L);
        FixtureListingSourceAdapter source = new FixtureListingSourceAdapter(properties);
        var result = source.search(new ListingBusinessSearchRequest("domain-parallel-v1", "模拟房源", null, 10),
                new ListingAccessScope(7, 9L, false));

        assertEquals(3, result.items().size());
        assertTrue(result.items().stream().allMatch(item -> item.dataMode() == ListingDataMode.SIMULATED));
        assertEquals("fixture", result.items().get(0).listingRef().sourceSystem());
        assertNull(result.nextCursor());
        assertTrue(source.search(new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10),
                new ListingAccessScope(7, 10L, false)).items().isEmpty());
        assertEquals(ListingSourceStatus.NOT_ACCESSIBLE, source.batchRead(new ListingSnapshotBatchReadRequest(
                "domain-parallel-v1", List.of(new com.bkanent.common.listing.ListingSourceVersionRef(
                result.items().get(0).listingRef(), null))), new ListingAccessScope(7, 10L, false)).get(0).status());
        assertEquals(0, source.search(new ListingBusinessSearchRequest("domain-parallel-v1", "不存在", null, 10),
                new ListingAccessScope(7, 9L, false)).items().size());
    }

    @Test
    void legacyMapsLongIdAndWanPriceToUnknownStableReference() {
        ListingMapper mapper = mock(ListingMapper.class);
        ListingEntity row = listing(41L, "4.2");
        when(mapper.selectList(any())).thenReturn(List.of(row));
        ListingSourceProperties properties = new ListingSourceProperties();
        LegacyLocalListingSourceAdapter source = new LegacyLocalListingSourceAdapter(mapper, properties);

        var result = source.search(new ListingBusinessSearchRequest("domain-parallel-v1", "社区", null, 10),
                new ListingAccessScope(7, 9L, false));
        var snapshot = result.items().get(0);

        assertEquals(new ListingRef("legacy-local", "41"), snapshot.listingRef());
        assertEquals(ListingDataMode.UNKNOWN, snapshot.dataMode());
        assertEquals("42000", snapshot.facts().totalPrice().amount());
        assertEquals("YUAN", snapshot.facts().totalPrice().unit().name());
        assertFalse(snapshot.facts().attributes().containsKey("ownerName"));
    }

    @Test
    void legacyCheckReturnsStaleInsteadOfTurningVersionChangeIntoReadConflict() {
        ListingMapper mapper = mock(ListingMapper.class);
        when(mapper.selectBatchIds(any())).thenReturn(List.of(listing(41L, "4.2")));
        LegacyLocalListingSourceAdapter source = new LegacyLocalListingSourceAdapter(mapper, new ListingSourceProperties());

        var result = source.check(new ListingSnapshotCheckRequest("domain-parallel-v1", List.of(
                new com.bkanent.common.listing.ListingSourceVersionRef(new ListingRef("legacy-local", "41"), "old-revision"))),
                new ListingAccessScope(7, 9L, false));

        assertEquals(ListingSourceStatus.STALE, result.get(0).status());
    }

    @Test
    void upstreamModeIsConfigurationControlledAndHttpFailureDoesNotFallback() throws IOException {
        String body = """
                {"success":true,"code":"0","message":"OK","data":{"contractVersion":"domain-parallel-v1","items":[
                  {"listingRef":{"sourceSystem":"provider-a","sourceId":"external-01"},
                   "contentHash":"0000000000000000000000000000000000000000000000000000000000000000",
                   "observedAt":"2026-10-01T00:00:00Z","dataMode":"REAL","datasetId":"provider-a-v1",
                   "facts":{"title":"小区房源","areaSquareMetres":"88.5",
                     "totalPrice":{"amount":"3200000","currency":"CNY","unit":"YUAN"},
                     "attributes":{},"unknownFields":[]}}],"nextCursor":null}}
                """;
        startServer(200, body, 0);
        ListingSourceProperties properties = httpProperties();
        properties.getHttp().setConfirmedDataMode(ListingDataMode.UNKNOWN);
        HttpListingSourceAdapter source = new HttpListingSourceAdapter(properties);

        var snapshot = source.search(new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10),
                new ListingAccessScope(7, 9L, false)).items().get(0);

        assertEquals(ListingDataMode.UNKNOWN, snapshot.dataMode());
        assertEquals("external-01", snapshot.listingRef().sourceId());
        assertEquals(snapshot.contentHash(), snapshot.revision());
        assertNotEquals("0000000000000000000000000000000000000000000000000000000000000000", snapshot.contentHash());
        assertThatHeadersContain(Map.of("X-Listing-Actor-Id", "7", "X-Listing-Read-Scope", "broker",
                "X-Listing-Broker-Id", "9"));
        server.stop(0);
        server = null;
        startServer(503, "unavailable", 0);
        properties = httpProperties();
        source = new HttpListingSourceAdapter(properties);
        HttpListingSourceAdapter selectedHttpSource = source;
        assertThrows(ListingSourceUnavailableException.class, () -> selectedHttpSource.search(
                new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10),
                new ListingAccessScope(7, 9L, false)));
    }

    @Test
    void upstreamTimeoutIsMappedToUnavailable() throws IOException {
        startServer(200, "{}", 250);
        ListingSourceProperties properties = httpProperties();
        properties.getHttp().setReadTimeoutMs(50);
        HttpListingSourceAdapter source = new HttpListingSourceAdapter(properties);

        assertThrows(ListingSourceUnavailableException.class, () -> source.search(
                new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10),
                new ListingAccessScope(7, 9L, false)));
    }

    @Test
    void managedHttpSourceUsesFrozenBatchReadAndCheckRoutesAndReconfirmsModeLocally() throws IOException {
        ListingRef ref = new ListingRef("provider-a", "external-02");
        String batchBody = """
                {"success":true,"code":"0","message":"OK","data":{"contractVersion":"domain-parallel-v1","items":[
                  {"listingRef":{"sourceSystem":"provider-a","sourceId":"external-02"},"status":"CURRENT",
                   "snapshot":{"listingRef":{"sourceSystem":"provider-a","sourceId":"external-02"},
                    "revision":"upstream-rev-2","contentHash":"0000000000000000000000000000000000000000000000000000000000000000",
                    "observedAt":"2026-10-01T00:00:00Z","dataMode":"REAL","datasetId":"provider-a-v1",
                    "facts":{"title":"接口房源","areaSquareMetres":"88.5",
                     "totalPrice":{"amount":"3200000","currency":"CNY","unit":"YUAN"},
                     "attributes":{},"unknownFields":[]}}}],"nextCursor":null}}
                """;
        String checkBody = """
                {"success":true,"code":"0","message":"OK","data":{"contractVersion":"domain-parallel-v1","items":[
                  {"listingRef":{"sourceSystem":"provider-a","sourceId":"external-02"},
                   "expectedRevision":"upstream-rev-2","status":"CURRENT"}],"nextCursor":null}}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                requestPath.set(exchange.getRequestURI().getPath());
                String body = requestPath.get().endsWith("/snapshots/batch-read") ? batchBody
                        : requestPath.get().endsWith("/snapshots/check") ? checkBody : "{}";
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();

        ListingSourceProperties properties = httpProperties();
        properties.getHttp().setConfirmedDataMode(ListingDataMode.SIMULATED);
        HttpListingSourceAdapter source = new HttpListingSourceAdapter(properties);
        var read = source.batchRead(new ListingSnapshotBatchReadRequest("domain-parallel-v1", List.of(
                new com.bkanent.common.listing.ListingSourceVersionRef(ref, null))),
                new ListingAccessScope(7, 9L, false));
        assertEquals("/listings/business/v1/snapshots/batch-read", requestPath.get());
        assertEquals(ListingSourceStatus.CURRENT, read.get(0).status());
        assertEquals(ListingDataMode.SIMULATED, read.get(0).snapshot().dataMode());
        assertNotEquals("0000000000000000000000000000000000000000000000000000000000000000",
                read.get(0).snapshot().contentHash());

        var checks = source.check(new ListingSnapshotCheckRequest("domain-parallel-v1", List.of(
                new com.bkanent.common.listing.ListingSourceVersionRef(ref, "upstream-rev-2"))),
                new ListingAccessScope(7, 9L, false));
        assertEquals("/listings/business/v1/snapshots/check", requestPath.get());
        assertEquals(ListingSourceStatus.CURRENT, checks.get(0).status());
        assertEquals("upstream-rev-2", checks.get(0).expectedRevision());
    }

    @Test
    void unavailableDatabaseDoesNotReturnFixtureFacts() {
        ListingMapper mapper = mock(ListingMapper.class);
        when(mapper.selectList(any())).thenThrow(new DataAccessResourceFailureException("offline"));
        LegacyLocalListingSourceAdapter source = new LegacyLocalListingSourceAdapter(mapper, new ListingSourceProperties());

        assertThrows(ListingSourceUnavailableException.class, () -> source.search(
                new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10),
                new ListingAccessScope(7, 9L, false)));
    }

    private void startServer(int status, String body, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/listings/business/v1/search", exchange -> {
            try {
                requestHeaders.set(Map.of("X-Listing-Actor-Id", String.valueOf(exchange.getRequestHeaders().getFirst("X-Listing-Actor-Id")),
                        "X-Listing-Read-Scope", String.valueOf(exchange.getRequestHeaders().getFirst("X-Listing-Read-Scope")),
                        "X-Listing-Broker-Id", String.valueOf(exchange.getRequestHeaders().getFirst("X-Listing-Broker-Id"))));
                if (delayMs > 0) Thread.sleep(delayMs);
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private void assertThatHeadersContain(Map<String, String> expected) {
        assertEquals(expected, requestHeaders.get());
    }

    private ListingSourceProperties httpProperties() {
        ListingSourceProperties properties = new ListingSourceProperties();
        properties.setProvider("http");
        properties.getHttp().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getHttp().setSourceSystem("provider-a");
        properties.getHttp().setReadTimeoutMs(1000);
        return properties;
    }

    private ListingEntity listing(long id, String priceWan) {
        ListingEntity row = new ListingEntity();
        row.setId(id);
        row.setBrokerId(9L);
        row.setDeleted(0);
        row.setStatus("ON_SALE");
        row.setTitle("社区测试房源");
        row.setAddress("示例地址");
        row.setLayout("2室1厅");
        row.setArea(new java.math.BigDecimal("80"));
        row.setTotalPrice(new java.math.BigDecimal(priceWan));
        row.setCreatedAt(LocalDateTime.of(2026, 10, 1, 8, 0));
        row.setOwnerName("不得导出");
        return row;
    }
}
