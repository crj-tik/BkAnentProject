package com.bkanent.compare.model;

/**
 * 对比表格列响应。
 */
public record CompareColumnResponse(
        /** 业务属性：fieldCode。 */
        String fieldCode,
        /** 业务属性：fieldName。 */
        String fieldName
) {
}
