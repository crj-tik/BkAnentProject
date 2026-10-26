package com.bkanent.media.model;

/**
 * 媒体生成任务消息对象；消息携带的授权是精确任务许可，worker仍需与本地任务记录对照。
 */
public record MediaGenerateTaskMessage(
        /** 业务属性：taskId。 */
        String taskId,
        /** Auth-signed scope plus service proof for this exact queued payload. */
        MediaBackgroundPermit permit
) {
}
