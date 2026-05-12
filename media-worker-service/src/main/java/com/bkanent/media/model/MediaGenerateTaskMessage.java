package com.bkanent.media.model;

import com.bkanent.common.model.MediaGenerateTaskRequest;

/**
 * 媒体生成任务消息对象。
 */
public record MediaGenerateTaskMessage(
        /** 业务属性：taskId。 */
        String taskId,
        /** 业务属性：request。 */
        MediaGenerateTaskRequest request
) {
}
