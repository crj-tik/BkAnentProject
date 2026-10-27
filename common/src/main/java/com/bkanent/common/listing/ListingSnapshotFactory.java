package com.bkanent.common.listing;

import com.bkanent.common.model.ListingDTO;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Shared canonical snapshot mapping for the explicitly scoped legacy-local source. */
public final class ListingSnapshotFactory {
    public static final String LEGACY_LOCAL_DATASET_ID = "legacy-local";

    private ListingSnapshotFactory() { }

    public static ListingSourceSnapshot legacyLocal(ListingDTO listing, Instant observedAt) {
        if (listing == null || listing.id() == null || listing.id() <= 0 || observedAt == null) {
            throw new IllegalArgumentException("Invalid legacy Listing source");
        }
        ListingRef ref = new ListingRef("legacy-local", Long.toString(listing.id()));
        Map<String, String> attributes = new java.util.LinkedHashMap<>();
        put(attributes, "layout", listing.layout());
        put(attributes, "floorLevel", listing.floorLevel());
        put(attributes, "decoration", listing.decoration());
        put(attributes, "schoolZone", listing.schoolZone());
        put(attributes, "traffic", listing.traffic());
        put(attributes, "verificationStatus", listing.verificationStatus());
        put(attributes, "status", listing.status());
        var unknown = new ArrayList<String>();
        if (listing.area() == null) unknown.add("areaSquareMetres");
        if (listing.totalPrice() == null) unknown.add("totalPrice");
        for (String field : java.util.List.of("layout", "floorLevel", "decoration", "schoolZone", "traffic",
                "verificationStatus", "status")) {
            if (!attributes.containsKey(field)) unknown.add(field);
        }
        ListingSnapshotFacts facts = new ListingSnapshotFacts(listing.title(), decimal(listing.area()),
                listing.totalPrice() == null ? null : new ListingMoney(
                decimal(listing.totalPrice().multiply(new BigDecimal("10000"))), "CNY", ListingMoney.Unit.YUAN),
                attributes, unknown);
        return create(ref, null, observedAt, ListingDataMode.UNKNOWN, LEGACY_LOCAL_DATASET_ID, facts);
    }

    public static ListingSourceSnapshot create(ListingRef ref, String revision, Instant observedAt,
                                                ListingDataMode mode, String datasetId,
                                                ListingSnapshotFacts facts) {
        String hash = contentHash(ref, mode, datasetId, facts);
        return new ListingSourceSnapshot(ref, revision == null ? hash : revision, hash,
                observedAt, mode, datasetId, facts);
    }

    public static String contentHash(ListingRef ref, ListingDataMode mode, String datasetId,
                                     ListingSnapshotFacts facts) {
        if (ref == null || mode == null || datasetId == null || facts == null) {
            throw new IllegalArgumentException("Invalid listing source facts");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                field(output, ref.sourceSystem());
                field(output, ref.sourceId());
                field(output, mode.name());
                field(output, datasetId);
                field(output, facts.title());
                field(output, facts.areaSquareMetres());
                field(output, facts.totalPrice() == null ? null : facts.totalPrice().amount());
                field(output, facts.totalPrice() == null ? null : facts.totalPrice().currency());
                field(output, facts.totalPrice() == null ? null : facts.totalPrice().unit().name());
                for (Map.Entry<String, String> item : new TreeMap<>(facts.attributes()).entrySet()) {
                    field(output, item.getKey());
                    field(output, item.getValue());
                }
                field(output, null);
                for (String unknown : facts.unknownFields().stream().sorted().toList()) field(output, unknown);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Unable to calculate listing content hash", failure);
        }
    }

    public static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static void put(Map<String, String> values, String field, String value) {
        if (value != null && !value.isBlank()) values.put(field, value.strip());
    }

    private static void field(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
