package com.bkanent.customer.enums;

/**
 * 买方房源咨询验证状态枚举，与业务状态独立。
 * 只有 CONFIRMED 对外表现为创建成功；NEEDS_RECHECK 是系统内部过渡状态。
 */
public enum InquiryValidationStateEnum {

    PENDING,
    CONFIRMED,
    NEEDS_RECHECK,
    REJECTED;

    public static boolean contains(String value) {
        for (InquiryValidationStateEnum stateEnum : values()) {
            if (stateEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
