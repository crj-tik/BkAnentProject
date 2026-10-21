package com.bkanent.common.a2a;

import com.bkanent.common.memory.MemoryExportReceipt;

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
        List<String> nextHints,
        MemoryExportReceipt memoryExport
) {

    public A2aOutput(String outputMode, Map<String, Object> data, String text, String summary, List<String> nextHints) {
        this(outputMode, data, text, summary, nextHints, null);
    }

    public boolean structured() {
        return data != null;
    }

    public A2aOutput withMemoryExport(MemoryExportReceipt receipt) {
        return new A2aOutput(outputMode, data, text, summary, nextHints, receipt);
    }
}
