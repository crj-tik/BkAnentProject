package com.bkanent.common.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体任务结果对象。
 */
public record MediaTaskResultDTO(
        /** 业务属性：taskId。 */
        String taskId,
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：contentId。 */
        Long contentId,
        /** 业务属性：callerService。 */
        String callerService,
        /** 业务属性：taskType。 */
        String taskType,
        /** 业务属性：status。 */
        String status,
        /** 业务属性：assetUrls。 */
        List<String> assetUrls,
        /** 业务属性：coverImageUrl。 */
        String coverImageUrl,
        /** 业务属性：videoUrl。 */
        String videoUrl,
        /** 业务属性：message。 */
        String message,
        /** 业务属性：createdAt。 */
        LocalDateTime createdAt,
        /** 业务属性：finishedAt。 */
        LocalDateTime finishedAt
) {
}
