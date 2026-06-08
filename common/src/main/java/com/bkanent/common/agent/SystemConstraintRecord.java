package com.bkanent.common.agent;

/**
 * SystemConstraintRecord 表示一条系统约束记录。
 */
public record SystemConstraintRecord(
        String constraintKey,
        String constraintText,
        String category,
        String source,
        String tags,
        Boolean active
) {
}
