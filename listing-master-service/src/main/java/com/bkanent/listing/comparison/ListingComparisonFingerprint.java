package com.bkanent.listing.comparison;

import com.bkanent.common.listing.ListingSourceSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class ListingComparisonFingerprint {
    private ListingComparisonFingerprint() { }

    static String sourceVersionHash(List<ListingSourceSnapshot> sources) {
        String canonical = sources.stream()
                .sorted(Comparator.comparing((ListingSourceSnapshot item) -> item.listingRef().sourceSystem())
                        .thenComparing(item -> item.listingRef().sourceId()))
                .map(item -> item.listingRef().sourceSystem() + "\u0000" + item.listingRef().sourceId()
                        + "\u0000" + item.revision() + "\u0000" + item.contentHash())
                .reduce((left, right) -> left + "\u0001" + right).orElse("");
        return sha256("listing-source-v1\u0000" + canonical);
    }

    static String cacheKey(List<ListingSourceSnapshot> sources, String rulesVersion, boolean includeConclusion) {
        return sha256("listing-comparison-v1\u0000" + rulesVersion + "\u0000" + includeConclusion
                + "\u0000" + sourceVersionHash(sources));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
