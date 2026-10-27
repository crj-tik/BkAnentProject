package com.bkanent.common.listing;

import java.io.Serializable;

/** Stable identity for a listing across provider changes. */
public record ListingRef(String sourceSystem, String sourceId) implements Serializable {
    public ListingRef {
        if (sourceSystem == null || !sourceSystem.matches("[a-z][a-z0-9.-]{0,95}")) {
            throw new IllegalArgumentException("Invalid sourceSystem");
        }
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 255
                || !sourceId.equals(sourceId.strip()) || sourceId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid sourceId");
        }
    }
}
