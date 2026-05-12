package com.bkanent.customer.enums;

/**
 * 跟进方式枚举。
 */
public enum FollowTypeEnum {

    PHONE,
    WECHAT,
    SHOWING,
    VISIT;

    public static boolean contains(String value) {
        for (FollowTypeEnum typeEnum : values()) {
            if (typeEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
