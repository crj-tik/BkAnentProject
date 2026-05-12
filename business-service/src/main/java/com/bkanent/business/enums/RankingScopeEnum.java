package com.bkanent.business.enums;

/**
 * 排行榜范围枚举。
 */
public enum RankingScopeEnum {

    PERSONAL,
    STORE,
    REGION;

    public static boolean contains(String value) {
        for (RankingScopeEnum scopeEnum : values()) {
            if (scopeEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
