package com.bkanent.common.agent;

/**
 * ArtifactQueryResponse 表示任务产物查询结果。
 */
public record ArtifactQueryResponse(
        ArtifactMeta meta,
        Object content
) {
}
