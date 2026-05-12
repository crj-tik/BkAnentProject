package com.bkanent.marketing.enums;

/**
 * 营销内容发布状态枚举。
 */
public enum MarketingPublishStatusEnum {

    DRAFT,
    REVIEWING,
    SUCCESS,
    FAILED,
    TAKEN_DOWN;

    public static boolean contains(String value) {
        for (MarketingPublishStatusEnum statusEnum : values()) {
            if (statusEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
