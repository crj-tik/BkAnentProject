package com.bkanent.listing.source;

import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingDataMode;
import com.bkanent.common.listing.ListingMoney;
import com.bkanent.common.listing.ListingRef;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSnapshotCheckRequest;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;
import com.bkanent.common.listing.ListingSnapshotFacts;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.listing.ListingSourceStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic fixture dataset. It is only active when explicitly selected and is always SIMULATED. */
@Component
@ConditionalOnProperty(prefix = "listing.source", name = "provider", havingValue = "fixture")
public class FixtureListingSourceAdapter implements ListingSourceAdapter {
    private final List<ListingSourceSnapshot> fixtures;
    private final long brokerId;

    public FixtureListingSourceAdapter(ListingSourceProperties properties) {
        if (properties.getFixtureBrokerId() <= 0) throw new IllegalStateException("Invalid fixture broker id");
        this.brokerId = properties.getFixtureBrokerId();
        this.fixtures = List.of(
                fixture("listing-001", "模拟房源一号", "89.5", "3200000", "2室1厅"),
                fixture("listing-002", "模拟房源二号", "104", "3650000", "3室1厅"),
                fixture("listing-003", "模拟房源三号", null, "4100000", null));
    }

    @Override
    public ListingSourcePage search(ListingBusinessSearchRequest request, ListingAccessScope scope) {
        String cursorId = decodeCursor(request.cursor());
        String keyword = request.keyword() == null ? "" : request.keyword().strip().toLowerCase(Locale.ROOT);
        List<ListingSourceSnapshot> matches = fixtures.stream()
                .filter(item -> readable(scope))
                .filter(item -> item.listingRef().sourceId().compareTo(cursorId == null ? "" : cursorId) > 0)
                .filter(item -> keyword.isEmpty() || item.facts().title().toLowerCase(Locale.ROOT).contains(keyword)
                        || item.facts().attributes().getOrDefault("layout", "").toLowerCase(Locale.ROOT).contains(keyword))
                .toList();
        boolean more = matches.size() > request.limit();
        List<ListingSourceSnapshot> items = matches.stream().limit(request.limit()).toList();
        String next = more && !items.isEmpty() ? encodeCursor(items.get(items.size() - 1).listingRef().sourceId()) : null;
        return new ListingSourcePage(items, next);
    }

    @Override
    public List<ListingSnapshotBatchReadItem> batchRead(ListingSnapshotBatchReadRequest request,
                                                       ListingAccessScope scope) {
        return request.refs().stream().map(ref -> {
            ListingSourceSnapshot found = fixtures.stream().filter(item -> item.listingRef().equals(ref.listingRef()))
                    .findFirst().orElse(null);
            if (found == null || !readable(scope)) return new ListingSnapshotBatchReadItem(
                    ref.listingRef(), ListingSourceStatus.NOT_ACCESSIBLE, null);
            if (ref.expectedRevision() != null && !ref.expectedRevision().equals(found.revision())) {
                throw ListingBusinessApiException.sourceVersionConflict();
            }
            return new ListingSnapshotBatchReadItem(ref.listingRef(), ListingSourceStatus.CURRENT, found);
        }).toList();
    }

    @Override
    public List<ListingSnapshotCheckItem> check(ListingSnapshotCheckRequest request, ListingAccessScope scope) {
        return request.refs().stream().map(ref -> {
            ListingSourceSnapshot found = fixtures.stream().filter(item -> item.listingRef().equals(ref.listingRef()))
                    .findFirst().orElse(null);
            ListingSourceStatus status = found == null || !readable(scope) ? ListingSourceStatus.NOT_ACCESSIBLE
                    : ref.expectedRevision().equals(found.revision()) ? ListingSourceStatus.CURRENT : ListingSourceStatus.STALE;
            return new ListingSnapshotCheckItem(ref.listingRef(), ref.expectedRevision(), status);
        }).toList();
    }

    private boolean readable(ListingAccessScope scope) {
        return scope != null && (scope.businessWideRead() || scope.brokerId() == brokerId);
    }

    private static ListingSourceSnapshot fixture(String id, String title, String area, String price, String layout) {
        Map<String, String> attributes = layout == null ? Map.of() : Map.of("layout", layout);
        List<String> unknown = area == null ? List.of("areaSquareMetres") : List.of();
        ListingSnapshotFacts facts = new ListingSnapshotFacts(title, area,
                new ListingMoney(price, "CNY", ListingMoney.Unit.YUAN), attributes, unknown);
        ListingRef ref = new ListingRef("fixture", id);
        return ListingSourceSnapshotFactory.create(ref, "fixture-v1-" + id, Instant.parse("2026-10-01T00:00:00Z"),
                ListingDataMode.SIMULATED, "fixture-v1", facts);
    }

    private static String encodeCursor(String id) {
        return "fixture:" + Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (!cursor.startsWith("fixture:")) throw new IllegalArgumentException();
            return new String(Base64.getUrlDecoder().decode(cursor.substring("fixture:".length())), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw ListingBusinessApiException.invalidArgument();
        }
    }
}
