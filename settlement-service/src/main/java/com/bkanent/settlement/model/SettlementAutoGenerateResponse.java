package com.bkanent.settlement.model;

import java.util.List;

/**
 * 自动生成结算响应。
 */
public record SettlementAutoGenerateResponse(
        /** 业务属性：statMonth。 */
        String statMonth,
        /** 业务属性：generatedCount。 */
        Integer generatedCount,
        /** 业务属性：settlements。 */
        List<SettlementDetailResponse> settlements
) {
}
