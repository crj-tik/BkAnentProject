package com.bkanent.marketing.enums;

/**
 * 营销内容审核状态枚举。
 */
public enum MarketingAuditStatusEnum {

    DRAFT,
    APPROVED,
    REJECTED,
    REVIEWING;

    public static boolean contains(String value) {
        for (MarketingAuditStatusEnum statusEnum : values()) {
            if (statusEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
