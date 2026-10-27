package com.bkanent.listing.comparison;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingMoney;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotFactory;
import com.bkanent.common.listing.ListingSnapshotFacts;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.listing.comparison.config.ListingComparisonProperties;
import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;
import com.bkanent.listing.comparison.model.ListingComparisonRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ListingComparisonReportStoreTest {
    @TempDir Path directory;

    @Test
    void cachesNewOwnerBoundReportsAndPreservesLegacyShareFilesByteForByte() throws Exception {
        String shareCode = "a".repeat(32);
        Path legacyShare = directory.resolve("legacy-share.json");
        Path legacyWithoutOwner = directory.resolve("legacy-no-owner.json");
        Path legacyPdf = directory.resolve(shareCode + ".pdf");
        byte[] shareMetadataBytes = ("{\"reportId\":\"legacy-report\",\"ownerActorId\":\"7\","
                + "\"shareCode\":\"" + shareCode + "\",\"publicShare\":true}").getBytes(StandardCharsets.UTF_8);
        byte[] noOwnerBytes = "{\"reportId\":\"legacy-orphan\",\"listingIds\":[101,102]}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] legacyPdfBytes = "%PDF-legacy-preserved".getBytes(StandardCharsets.UTF_8);
        Files.write(legacyShare, shareMetadataBytes);
        Files.write(legacyWithoutOwner, noOwnerBytes);
        Files.write(legacyPdf, legacyPdfBytes);

        ListingComparisonReportStore store = store();
        store.initialize();
        var inventory = store.legacyInventory();
        assertEquals(2, inventory.metadataFiles());
        assertEquals(1, inventory.ownedFiles());
        assertEquals(1, inventory.filesWithoutOwner());
        assertEquals(1, inventory.pdfFiles());

        List<ListingSourceSnapshot> sources = List.of(snapshot("legacy-local", "101", "r1"),
                snapshot("provider-a", "101", "rev-1"));
        String sourceHash = ListingComparisonFingerprint.sourceVersionHash(sources);
        var first = report("b".repeat(32), sources);
        var saved = store.createIfAbsent("7", "c".repeat(64), sourceHash, first);
        var duplicate = store.createIfAbsent("7", "c".repeat(64), sourceHash, report("d".repeat(32), sources));

        assertEquals(first.reportId(), saved.reportId());
        assertEquals(first.reportId(), duplicate.reportId());
        try (var files = Files.list(directory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".listing.json")).count());
        }
        assertTrue(new String(store.loadPdf(first.reportId(), "7").getContentAsByteArray(), StandardCharsets.ISO_8859_1)
                .startsWith("%PDF-1.4"));
        assertEquals(AuthzErrorCode.PERMISSION_DENIED,
                assertThrows(AuthzException.class, () -> store.findOwned(first.reportId(), "8")).code());
        assertEquals(AuthzErrorCode.PERMISSION_DENIED,
                assertThrows(AuthzException.class, () -> store.loadPdf(first.reportId(), "8")).code());
        assertEquals(AuthzErrorCode.PERMISSION_DENIED,
                assertThrows(AuthzException.class, () -> store.findOwned(shareCode, "7")).code());
        assertEquals(AuthzErrorCode.PERMISSION_DENIED,
                assertThrows(AuthzException.class, () -> store.loadPdf(shareCode, "7")).code());
        assertArrayEquals(shareMetadataBytes, Files.readAllBytes(legacyShare));
        assertArrayEquals(noOwnerBytes, Files.readAllBytes(legacyWithoutOwner));
        assertArrayEquals(legacyPdfBytes, Files.readAllBytes(legacyPdf));

        ListingComparisonReportStore restarted = store();
        restarted.initialize();
        assertEquals(first.reportId(), restarted.findOwned(first.reportId(), "7").reportId());
        assertArrayEquals(shareMetadataBytes, Files.readAllBytes(legacyShare));
        assertArrayEquals(noOwnerBytes, Files.readAllBytes(legacyWithoutOwner));
        assertArrayEquals(legacyPdfBytes, Files.readAllBytes(legacyPdf));
    }

    private ListingComparisonReportStore store() {
        var properties = new ListingComparisonProperties();
        properties.setStorageDir(directory.toString());
        return new ListingComparisonReportStore(new ObjectMapper().findAndRegisterModules(), properties,
                new ListingComparisonPdfService());
    }

    private ListingComparisonReportResponse report(String reportId, List<ListingSourceSnapshot> sources) {
        return new ListingComparisonReportResponse(reportId, "domain-parallel-v1", "listing-comparison-v1",
                Instant.parse("2026-10-27T00:00:00Z"), ListingDataMode.UNKNOWN, sources,
                List.of(new ListingComparisonRow(sources.get(0).listingRef(), "测试房源", Map.of("unitPrice", "32000"))),
                List.of(), "| comparison |", "未知来源", "/listings/comparisons/" + reportId + "/pdf");
    }

    private ListingSourceSnapshot snapshot(String system, String id, String revision) {
        return ListingSnapshotFactory.create(new ListingRef(system, id), revision,
                Instant.parse("2026-10-27T00:00:00Z"), ListingDataMode.UNKNOWN, "dataset-v1",
                new ListingSnapshotFacts("测试房源", "100", new ListingMoney("3200000", "CNY",
                        ListingMoney.Unit.YUAN), Map.of(), List.of()));
    }
}
