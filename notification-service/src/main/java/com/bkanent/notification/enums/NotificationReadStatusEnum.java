package com.bkanent.notification.enums;

/**
 * 站内消息已读状态枚举。
 */
public enum NotificationReadStatusEnum {

    UNREAD,
    READ;

    public static boolean contains(String value) {
        for (NotificationReadStatusEnum readStatusEnum : values()) {
            if (readStatusEnum.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
