package com.bkanent.listing.comparison;

import com.bkanent.common.listing.ListingRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListingLegacyReferenceMapperTest {
    @Test
    void mapsNumericIdsLosslesslyAndOnlyToLegacyLocal() {
        assertEquals(List.of(new ListingRef("legacy-local", "101"),
                        new ListingRef("legacy-local", "9223372036854775807")),
                ListingLegacyReferenceMapper.fromIds(List.of(101L, Long.MAX_VALUE)));
    }

    @Test
    void rejectsInvalidRangeNullIdsAndDuplicates() {
        assertThrows(ListingComparisonException.class, () -> ListingLegacyReferenceMapper.fromIds(null));
        assertThrows(ListingComparisonException.class, () -> ListingLegacyReferenceMapper.fromIds(List.of(101L)));
        assertThrows(ListingComparisonException.class,
                () -> ListingLegacyReferenceMapper.fromIds(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L)));
        assertThrows(ListingComparisonException.class, () -> ListingLegacyReferenceMapper.fromIds(List.of(1L, 1L)));
        assertThrows(ListingComparisonException.class, () -> ListingLegacyReferenceMapper.fromIds(java.util.Arrays.asList(1L, null)));
        assertThrows(ListingComparisonException.class, () -> ListingLegacyReferenceMapper.fromIds(List.of(0L, 1L)));
    }
}
