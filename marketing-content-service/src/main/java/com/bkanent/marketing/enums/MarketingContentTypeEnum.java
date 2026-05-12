package com.bkanent.marketing.enums;

/**
 * 营销内容类型枚举。
 */
public enum MarketingContentTypeEnum {

    TEXT,
    IMAGE,
    VIDEO,
    MIXED;

    public static boolean contains(String value) {
        for (MarketingContentTypeEnum typeEnum : values()) {
            if (typeEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
