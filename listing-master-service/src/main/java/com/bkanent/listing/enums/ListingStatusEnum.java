package com.bkanent.listing.enums;

/**
 * ListingStatusEnum 房源状态枚举定义。
 */
public enum ListingStatusEnum {

    PENDING_REVIEW,
    ON_SALE,
    SOLD,
    OFFLINE;

    public static boolean contains(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (ListingStatusEnum statusEnum : values()) {
            if (statusEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
