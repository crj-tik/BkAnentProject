package com.bkanent.customer.model;

import java.time.LocalDateTime;

/**
 * 买方咨询 × 对比报告关联视图（仅 reportId 与关联时间，不含报告正文；正文按需经对比读取接口获取）。
 */
public record CustomerInquiryComparisonView(
        /** 业务属性：reportId（对比报告 ID，32 位十六进制）。 */
        String reportId,
        /** 业务属性：createdAt（关联时间）。 */
        LocalDateTime createdAt
) {
}
