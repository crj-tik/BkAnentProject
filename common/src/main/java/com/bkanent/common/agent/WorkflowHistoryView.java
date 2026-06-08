package com.bkanent.common.agent;

import java.util.List;
import java.util.Map;

/**
 * WorkflowHistoryView 表示一次工作流中按时间排列的步骤历史和上游产出摘要。
 */
public record WorkflowHistoryView(
        List<Map<String, Object>> steps,
        List<Map<String, Object>> artifactSummaries
) {
}
