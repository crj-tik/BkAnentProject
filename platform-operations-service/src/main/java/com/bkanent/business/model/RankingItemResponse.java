package com.bkanent.business.model;

import java.math.BigDecimal;

/**
 * 排行榜项响应。
 */
public record RankingItemResponse(
        /** 业务属性：rankNo。 */
        Integer rankNo,
        /** 业务属性：rankingScope。 */
        String rankingScope,
        /** 业务属性：subjectName。 */
        String subjectName,
        /** 业务属性：scoreValue。 */
        BigDecimal scoreValue
) {
}
