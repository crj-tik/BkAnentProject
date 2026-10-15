package com.bkanent.common.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Pure calculations over an already authorized snapshot. totalPrice is stored in 万元. */
public final class ListingComparisonFacts {
    private ListingComparisonFacts() { }

    public static BigDecimal unitPriceYuan(ListingDTO listing) {
        if (listing.totalPrice() == null || listing.area() == null || listing.area().signum() <= 0) return null;
        return listing.totalPrice().multiply(new BigDecimal("10000"))
                .divide(listing.area(), 2, RoundingMode.HALF_UP);
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
