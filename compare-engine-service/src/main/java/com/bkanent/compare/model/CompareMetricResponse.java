package com.bkanent.compare.model;

/**
 * 房源对比指标响应。
 */
public record CompareMetricResponse(
        /** 业务属性：metricName。 */
        String metricName,
        /** 业务属性：summaryValue。 */
        String summaryValue
) {
}
