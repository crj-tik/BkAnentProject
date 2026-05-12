package com.bkanent.compare.model;

import java.time.LocalDateTime;

/**
 * 房源对比报告缓存条目。
 */
public record CompareReportCacheEntry(
        /** 业务属性：cacheKey。 */
        String cacheKey,
        /** 业务属性：shareCode。 */
        String shareCode,
        /** 业务属性：pdfFileName。 */
        String pdfFileName,
        /** 业务属性：report。 */
        CompareReportResponse report,
        /** 业务属性：createdAt。 */
        LocalDateTime createdAt
) {
}
