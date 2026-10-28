package com.bkanent.common.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import com.bkanent.common.listing.ListingMoney;
import com.bkanent.common.listing.ListingSourceSnapshot;

/** Pure calculations over an already authorized snapshot. totalPrice is stored in 万元. */
public final class ListingComparisonFacts {
    private ListingComparisonFacts() { }

    public static BigDecimal unitPriceYuan(ListingDTO listing) {
        if (listing.totalPrice() == null || listing.area() == null || listing.area().signum() <= 0) return null;
        return listing.totalPrice().multiply(new BigDecimal("10000"))
                .divide(listing.area(), 2, RoundingMode.HALF_UP);
    }

    /** Deterministic unit-price calculation over the stable source contract's CNY/YUAN facts. */
    public static BigDecimal unitPriceYuan(ListingSourceSnapshot listing) {
        if (listing == null || listing.facts().totalPrice() == null
                || listing.facts().totalPrice().unit() != ListingMoney.Unit.YUAN
                || !"CNY".equals(listing.facts().totalPrice().currency())
                || listing.facts().areaSquareMetres() == null) return null;
        BigDecimal area = new BigDecimal(listing.facts().areaSquareMetres());
        if (area.signum() <= 0) return null;
        return new BigDecimal(listing.facts().totalPrice().amount())
                .divide(area, 2, RoundingMode.HALF_UP);
    }

    /** Stable identity and source revision, rather than an old Long-ID DTO, define the Studio fingerprint. */
    public static String sourceFingerprint(List<ListingSourceSnapshot> listings, boolean includeConclusion) {
        try {
            String snapshot = "listing-source-v1|conclusion=" + includeConclusion + "|" + listings.stream()
                    .sorted(Comparator.comparing((ListingSourceSnapshot item) -> item.listingRef().sourceSystem())
                            .thenComparing(item -> item.listingRef().sourceId()))
                    .map(item -> item.listingRef().sourceSystem() + ":" + item.listingRef().sourceId() + ":"
                            + item.revision() + ":" + item.contentHash() + ":" + item.dataMode().name())
                    .reduce((left, right) -> left + "|" + right).orElse("");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(snapshot.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Versioned snapshot fingerprint; never an authorization grant or a user-independent read cache. */
    public static String fingerprint(List<ListingDTO> listings, boolean includeAi) {
        try {
            String snapshot = "compare-yuan-v2|ai=" + includeAi + "|" + listings.stream()
                    .sorted(Comparator.comparing(ListingDTO::id)).toList();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(snapshot.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
