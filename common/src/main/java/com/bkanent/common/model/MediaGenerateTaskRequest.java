package com.bkanent.common.model;

import java.util.List;

/**
 * 媒体生成任务请求对象。
 */
public record MediaGenerateTaskRequest(
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：contentId。 */
        Long contentId,
        /** 业务属性：callerService。 */
        String callerService,
        /** 业务属性：taskType。 */
        String taskType,
        /** 业务属性：prompt。 */
        String prompt,
        /** 业务属性：angles。 */
        List<String> angles,
        /** 业务属性：expectedAssetCount。 */
        Integer expectedAssetCount
) {
}
