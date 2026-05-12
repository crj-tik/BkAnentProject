package com.bkanent.notification.enums;

/**
 * 通知渠道枚举。
 */
public enum NotificationChannelEnum {

    STATION,
    EMAIL,
    WECOM,
    DINGTALK;

    public static boolean contains(String value) {
        for (NotificationChannelEnum channelEnum : values()) {
            if (channelEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
