package com.bkanent.compare.service.impl;

import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.model.ListingComparisonFacts;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.compare.config.CompareReportProperties;
import com.bkanent.compare.model.CompareReportResponse;
import com.bkanent.compare.service.CompareReportPdfService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompareReportCacheServiceImplTest {
    @TempDir Path temporaryDirectory;

    @Test
    void cacheIsOwnerScopedAndPublicShareRequiresExplicitOwnerAction() {
        CompareReportCacheServiceImpl cache = cache();
        ListingDTO listing = listing();
        CompareReportResponse created = cache.save("73", "b".repeat(64),
                ListingComparisonFacts.fingerprint(List.of(listing), false), report(listing));

        assertThat(cache.findByCacheKey("73", "b".repeat(64))).isPresent();
        assertThat(cache.findByCacheKey("74", "b".repeat(64))).isEmpty();
        assertThat(cache.findEntryByShareCode(created.shareCode()).publicShare()).isFalse();
        assertThatThrownBy(() -> cache.publishShare(created.shareCode(), "74"))
                .isInstanceOf(AuthzException.class);

        var shared = cache.publishShare(created.shareCode(), "73");
        assertThat(shared.publicShare()).isTrue();
        assertThat(cache.findEntryByShareCode(created.shareCode()).publicShare()).isTrue();
    }

    @Test
    void legacyUnownedCacheRecordIsNotRestoredAsPublic() throws Exception {
        cache();
        var legacy = new com.bkanent.compare.model.CompareReportCacheEntry("legacy-key", "legacy-share",
                "legacy-share.pdf", report(listing()), java.time.LocalDateTime.now());
        new ObjectMapper().findAndRegisterModules()
                .writeValue(temporaryDirectory.resolve("legacy-share.json").toFile(), legacy);

        CompareReportCacheServiceImpl restarted = newCache();
        restarted.init();
        assertThatThrownBy(() -> restarted.findEntryByShareCode("legacy-share"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CompareReportCacheServiceImpl cache() {
        CompareReportCacheServiceImpl cache = newCache();
        cache.init();
        return cache;
    }

    private CompareReportCacheServiceImpl newCache() {
        CompareReportProperties properties = new CompareReportProperties();
        properties.setStorageDir(temporaryDirectory.toString());
        CompareReportPdfService pdf = mock(CompareReportPdfService.class);
        when(pdf.generatePdf(org.mockito.ArgumentMatchers.any())).thenReturn(new byte[]{1, 2, 3});
        return new CompareReportCacheServiceImpl(new ObjectMapper().findAndRegisterModules(), properties, pdf);
    }

    private CompareReportResponse report(ListingDTO listing) {
        return new CompareReportResponse(List.of(listing), List.of(), List.of(), List.of(), "table", "summary",
                null, null, null);
    }

    private ListingDTO listing() {
        return new ListingDTO(101L, "湖畔雅苑", "地址", "3室2厅", new BigDecimal("90"),
                new BigDecimal("300"), "ON_SALE", "中层", "精装", "学校", "地铁", "已核验");
    }
}
