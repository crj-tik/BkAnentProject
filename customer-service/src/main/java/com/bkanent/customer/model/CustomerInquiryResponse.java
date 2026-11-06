package com.bkanent.customer.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 买方房源咨询响应。
 * comparisonReports 为关联的对比报告清单（reportId + 关联时间，按时间升序），不含报告正文。
 */
public record CustomerInquiryResponse(
        /** 业务属性：id。 */
        Long id,
        /** 业务属性：customerId。 */
        Long customerId,
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：brokerId（可信负责经纪人）。 */
        Long brokerId,
        /** 业务属性：inquiryChannel。 */
        String inquiryChannel,
        /** 业务属性：inquiryContent。 */
        String inquiryContent,
        /** 业务属性：status（业务状态）。 */
        String status,
        /** 业务属性：validationState（验证状态）。 */
        String validationState,
        /** 业务属性：checkedListingVersion（验证时房源版本）。 */
        String checkedListingVersion,
        /** 业务属性：checkedAt（验证时间）。 */
        LocalDateTime checkedAt,
        /** 业务属性：comparisonReports（关联对比报告清单，按关联时间升序）。 */
        List<CustomerInquiryComparisonView> comparisonReports
) {
    /**
     * 兼容构造器：不带关联清单的既有调用方编译不变，默认空清单。
     */
    public CustomerInquiryResponse(Long id, Long customerId, Long listingId, Long brokerId,
                                   String inquiryChannel, String inquiryContent, String status,
                                   String validationState, String checkedListingVersion,
                                   LocalDateTime checkedAt) {
        this(id, customerId, listingId, brokerId, inquiryChannel, inquiryContent, status,
                validationState, checkedListingVersion, checkedAt, List.of());
    }
}
