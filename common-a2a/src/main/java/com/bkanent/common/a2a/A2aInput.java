package com.bkanent.common.a2a;

import com.bkanent.common.agent.DurableMemoryExportDirective;

import java.util.Map;

/**
 * Parsed official A2A request input passed to a SubAgent.
 */
public record A2aInput(
        String instruction,
        Map<String, Object> structuredContext,
        Map<String, Object> metadata,
        boolean jsonOutput,
        DurableMemoryExportDirective durableMemoryExport
) {
    public A2aInput(String instruction, Map<String, Object> structuredContext, Map<String, Object> metadata,
                    boolean jsonOutput) {
        this(instruction, structuredContext, metadata, jsonOutput, null);
    }
}
