package com.bkanent.compare.model;

import java.util.Map;

/**
 * 对比表格行响应。
 */
public record CompareRowResponse(
        /** 业务属性：listingId。 */
        Long listingId,
        /** 业务属性：listingTitle。 */
        String listingTitle,
        /** 业务属性：values。 */
        Map<String, String> values
) {
}
