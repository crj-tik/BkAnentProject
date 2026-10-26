package com.bkanent.compare.model;

import java.time.LocalDateTime;

/**
 * 房源对比报告缓存条目。
 */
public record CompareReportCacheEntry(
        /** 业务属性：cacheKey。 */
        String cacheKey,
        /** Auth-verified user that owns this immutable report snapshot. */
        String ownerActorId,
        /** Hash of the exact Listing facts stored in this report. */
        String sourceVersionHash,
        /** Public sharing is enabled only by an explicit, separately authorized action. */
        boolean publicShare,
        /** 业务属性：shareCode。 */
        String shareCode,
        /** 业务属性：pdfFileName。 */
        String pdfFileName,
        /** 业务属性：report。 */
        CompareReportResponse report,
        /** 业务属性：createdAt。 */
        LocalDateTime createdAt
) {
    /** Old metadata remains private and unowned, so it cannot authorize access after upgrade. */
    public CompareReportCacheEntry(String cacheKey, String shareCode, String pdfFileName,
                                   CompareReportResponse report, LocalDateTime createdAt) {
        this(cacheKey, null, null, false, shareCode, pdfFileName, report, createdAt);
    }
}
