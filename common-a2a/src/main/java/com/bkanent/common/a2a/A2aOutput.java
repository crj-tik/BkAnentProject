package com.bkanent.common.a2a;

import java.util.List;
import java.util.Map;

/**
 * Canonicalized SubAgent result before it is converted to A2A parts.
 */
public record A2aOutput(
        String outputMode,
        Map<String, Object> data,
        String text,
        String summary,
        List<String> nextHints
) {

    public boolean structured() {
        return data != null;
    }
}
