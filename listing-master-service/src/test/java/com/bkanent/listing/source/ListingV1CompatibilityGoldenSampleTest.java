package com.bkanent.listing.source;

import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 任务 1.4：固化 v1 legacy-local 兼容黄金样本。
 *
 * 固定输入的 contentHash/revision 黄金值防止重构静默改变既有版本、报告指纹与 Studio/营销历史引用；
 * 旧 ListingDTO 保持万元单位，v1 快照在边界恰好换算一次（42000 元，不重复乘 10000）。
 * 报告/PDF 读取权复核回归由 ListingComparisonServiceTest#staleInaccessibleAndUnavailableSourcesBlockHistoricalBodyAndPdf 覆盖。
 */
class ListingV1CompatibilityGoldenSampleTest {

    /** 黄金值：固定样本（id=41，总价 4.2 万元）经规范化投影的 SHA-256。重构不应改变该值。 */
    private static final String GOLDEN_HASH_41 = "dd58ee21c753cf5ad69ceab957d5a5760494ac6bf49089a4d5895ce265c07c47";

    @Test
    void legacyLocalSnapshotGoldenSampleIsStableAcrossTime() {
        ListingMapper mapper = mock(ListingMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(fixedListing()));
        LegacyLocalListingSourceAdapter source =
                new LegacyLocalListingSourceAdapter(mapper, new ListingSourceProperties());

        var first = source.search(new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10),
                new ListingAccessScope(7, 9L, false)).items().get(0);
        var second = source.search(new ListingBusinessSearchRequest("domain-parallel-v1", null, null, 10),
                new ListingAccessScope(7, 9L, false)).items().get(0);

        assertEquals(new ListingRef("legacy-local", "41"), first.listingRef());
        assertEquals(ListingDataMode.UNKNOWN, first.dataMode());
        assertEquals("legacy-local", first.datasetId());
        assertEquals("42000", first.facts().totalPrice().amount());
        assertEquals("CNY", first.facts().totalPrice().currency());
        assertEquals("YUAN", first.facts().totalPrice().unit().name());
        assertEquals("80", first.facts().areaSquareMetres());
        // hash 不含 observedAt：不同时间计算结果一致，无外部 revision 时 revision == contentHash
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.contentHash(), first.revision());
        // 黄金值固定版本算法：算法或规范化投影变更必须显式版本化迁移，不得静默改变
        assertEquals(GOLDEN_HASH_41, first.contentHash());
        // 敏感字段不进入快照
        assertFalse(first.facts().attributes().containsKey("ownerName"));
        assertFalse(first.facts().attributes().containsKey("certificateNo"));
    }

    @Test
    void legacyDtoKeepsWanUnitForOldConsumers() {
        ListingConverter converter = new ListingConverter();
        ListingDTO dto = converter.toDto(fixedListing());

        assertEquals(41L, dto.id());
        // 旧 DTO 契约仍为万元：4.2 万元不得在旧接口被再次换算
        assertEquals(0, dto.totalPrice().compareTo(new BigDecimal("4.2")));
        assertEquals("社区测试房源", dto.title());
        assertEquals("ON_SALE", dto.status());
    }

    private ListingEntity fixedListing() {
        ListingEntity row = new ListingEntity();
        row.setId(41L);
        row.setBrokerId(9L);
        row.setDeleted(0);
        row.setStatus("ON_SALE");
        row.setTitle("社区测试房源");
        row.setAddress("示例地址");
        row.setLayout("2室1厅");
        row.setArea(new BigDecimal("80"));
        row.setTotalPrice(new BigDecimal("4.2"));
        row.setCreatedAt(LocalDateTime.of(2026, 10, 1, 8, 0));
        row.setOwnerName("不得导出");
        row.setCertificateNo("CERT-41");
        return row;
    }
}
