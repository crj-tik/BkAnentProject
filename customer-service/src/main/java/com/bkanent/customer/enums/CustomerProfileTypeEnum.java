package com.bkanent.customer.enums;

/**
 * 档案类型枚举。
 */
public enum CustomerProfileTypeEnum {

    CUSTOMER,
    OWNER;

    public static boolean contains(String value) {
        for (CustomerProfileTypeEnum typeEnum : values()) {
            if (typeEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
