package com.bkanent.common.agent;

import java.io.Serializable;

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
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
