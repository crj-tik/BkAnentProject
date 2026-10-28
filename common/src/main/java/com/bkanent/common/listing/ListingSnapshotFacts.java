package com.bkanent.common.listing;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Public, unit-explicit facts used by domain consumers. */
public record ListingSnapshotFacts(
        String title,
        String areaSquareMetres,
        ListingMoney totalPrice,
        Map<String, String> attributes,
        List<String> unknownFields) implements Serializable {
    public ListingSnapshotFacts {
        if (areaSquareMetres != null) {
            if (!areaSquareMetres.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
                    || new BigDecimal(areaSquareMetres).signum() < 0) {
                throw new IllegalArgumentException("Invalid areaSquareMetres");
            }
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        unknownFields = unknownFields == null ? List.of() : List.copyOf(unknownFields);
        if (unknownFields.stream().anyMatch(field -> field == null || field.isBlank())) {
            throw new IllegalArgumentException("Invalid unknown field name");
        }
    }
}
