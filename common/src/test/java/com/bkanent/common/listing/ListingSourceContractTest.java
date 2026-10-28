package com.bkanent.common.listing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ListingSourceContractTest {

    @Test
    void sameUpstreamIdFromDifferentSourcesRemainsDistinct() {
        ListingRef local = new ListingRef("legacy-local", "101");
        ListingRef upstream = new ListingRef("provider-a", "101");

        assertThat(local).isNotEqualTo(upstream);
    }

    @Test
    void listingIdsRemainOpaqueAndAreNotTrimmedOrCoerced() {
        ListingRef ref = new ListingRef("provider-a", "A-001/01");

        assertThat(ref.sourceId()).isEqualTo("A-001/01");
        assertThatIllegalArgumentException().isThrownBy(() -> new ListingRef("provider-a", " 101 "));
        assertThatIllegalArgumentException().isThrownBy(() -> new ListingRef("Provider-A", "101"));
    }

    @Test
    void moneyAndAreaAreExplicitDecimalStrings() {
        ListingSnapshotFacts facts = new ListingSnapshotFacts("Listing 101", "89.50",
                new ListingMoney("3200000", "CNY", ListingMoney.Unit.YUAN),
                Map.of("layout", "2室1厅"), List.of());

        assertThat(facts.areaSquareMetres()).isEqualTo("89.50");
        assertThat(facts.totalPrice().amount()).isEqualTo("3200000");
        assertThat(facts.totalPrice().unit()).isEqualTo(ListingMoney.Unit.YUAN);
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ListingMoney("3.2e6", "CNY", ListingMoney.Unit.YUAN));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ListingSnapshotFacts("Listing 101", "89 sqm", null, Map.of(), List.of()));
    }

    @Test
    void snapshotRequiresStableHashAndConfirmedMode() {
        ListingSourceSnapshot snapshot = new ListingSourceSnapshot(
                new ListingRef("legacy-local", "101"), "rev-1", "a".repeat(64),
                Instant.parse("2026-10-27T00:00:00Z"), ListingDataMode.UNKNOWN, "legacy-local",
                new ListingSnapshotFacts("Listing 101", null, null, Map.of(), List.of("areaSquareMetres")));

        assertThat(snapshot.dataMode()).isEqualTo(ListingDataMode.UNKNOWN);
        assertThat(snapshot.facts().unknownFields()).containsExactly("areaSquareMetres");
        assertThatIllegalArgumentException().isThrownBy(() -> new ListingSourceSnapshot(
                snapshot.listingRef(), snapshot.revision(), "not-a-hash", snapshot.observedAt(),
                snapshot.dataMode(), snapshot.datasetId(), snapshot.facts()));
    }
}
