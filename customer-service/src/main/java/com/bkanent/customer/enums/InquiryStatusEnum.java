package com.bkanent.customer.enums;

/**
 * 买方房源咨询业务状态枚举。
 * 允许 OPEN→FOLLOWING/CLOSED、FOLLOWING→CLOSED，CLOSED 为终态。
 */
public enum InquiryStatusEnum {

    OPEN,
    FOLLOWING,
    CLOSED;

    public static boolean contains(String value) {
        for (InquiryStatusEnum statusEnum : values()) {
            if (statusEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
