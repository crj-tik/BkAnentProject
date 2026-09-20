package com.bkanent.common.a2a;

import java.util.Map;

/**
 * Parsed official A2A request input passed to a SubAgent.
 */
public record A2aInput(
        String instruction,
        Map<String, Object> structuredContext,
        Map<String, Object> metadata,
        boolean jsonOutput
) {
}
