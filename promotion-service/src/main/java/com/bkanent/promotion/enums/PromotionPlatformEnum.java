package com.bkanent.promotion.enums;

/**
 * 宣传平台枚举。
 */
public enum PromotionPlatformEnum {

    XIAOHONGSHU,
    BEIKE,
    DOUYIN,
    FAIL_PLATFORM;

    public static boolean contains(String value) {
        for (PromotionPlatformEnum platformEnum : values()) {
            if (platformEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
