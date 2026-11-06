package com.bkanent.customer.model;

/**
 * 买方咨询关联对比报告请求。
 */
public record CustomerInquiryComparisonAttachRequest(
        /** 业务属性：reportId（待关联的对比报告 ID，32 位十六进制）。 */
        String reportId
) {
}
