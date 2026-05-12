package com.bkanent.customer.enums;

/**
 * 委托状态枚举。
 */
public enum EntrustStatusEnum {

    ACTIVE,
    EXPIRED,
    COMPLETED;

    public static boolean contains(String value) {
        for (EntrustStatusEnum statusEnum : values()) {
            if (statusEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
