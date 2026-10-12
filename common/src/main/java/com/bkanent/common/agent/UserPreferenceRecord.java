package com.bkanent.common.agent;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * UserPreferenceRecord 表示一条用户偏好记录。
 */
public record UserPreferenceRecord(
        String userId,
        String preferenceKey,
        String preferenceValue,
        String category,
        BigDecimal confidence,
        String evidence,
        String sourceSessionId,
        LocalDateTime lastObservedAt,
        Integer observationCount
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
