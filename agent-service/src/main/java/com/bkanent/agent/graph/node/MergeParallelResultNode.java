package com.bkanent.agent.graph.node;

import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MergeParallelResultNode {

    public SupervisorWorkflowState merge(SupervisorGraphState graphState,
                                         List<String> artifactIds,
                                         AgentTaskInvokeResponse response) {
        return new SupervisorWorkflowState(
                graphState.sessionId(),
                graphState.taskId(),
                graphState.traceId(),
                graphState.userId(),
                graphState.userMessage(),
                WorkflowStatus.RUNNING,
                "parallel:" + String.join(",", graphState.parallelDomains()),
                withParallelMetadata(graphState.sharedContext(), graphState.parallelDomains(), artifactIds, response),
                buildParallelHandoffHistory(graphState.parallelDomains(), graphState.traceId()),
                artifactIds,
                response,
                null,
                null,
                null
        );
    }

    private Map<String, Object> withParallelMetadata(Map<String, Object> context,
                                                     List<String> parallelDomains,
                                                     List<String> artifactIds,
                                                     AgentTaskInvokeResponse response) {
        Map<String, Object> merged = new LinkedHashMap<>(context);
        merged.put("parallelDomains", parallelDomains);
        merged.put("requireParallel", true);
        merged.put("latestArtifactIds", artifactIds);
        if (response != null && response.structuredOutput() != null) {
            merged.put("parallelStructuredOutput", response.structuredOutput());
            merged.put("artifactRefs", buildParallelArtifactRefs(artifactIds, response.structuredOutput()));
        }
        return Map.copyOf(merged);
    }

    private Map<String, Object> buildParallelArtifactRefs(List<String> artifactIds,
                                                          Map<String, Object> structuredOutput) {
        Map<String, Object> refs = new LinkedHashMap<>();
        if (artifactIds == null || artifactIds.isEmpty()) {
            return refs;
        }
        refs.put("latestPrimaryArtifactId", artifactIds.get(0));
        refs.put("latestArtifactCount", artifactIds.size());
        Object contentType = structuredOutput.get("contentType");
        if (contentType != null) {
            refs.put("latestArtifactType", String.valueOf(contentType));
        }
        if ("parallel_result".equalsIgnoreCase(String.valueOf(contentType))) {
            refs.put("parallelResultArtifactId", artifactIds.get(0));
        }
        return refs;
    }

    private List<Map<String, Object>> buildParallelHandoffHistory(List<String> parallelDomains, String traceId) {
        return parallelDomains.stream()
                .map(domain -> {
                    Map<String, Object> handoff = new java.util.LinkedHashMap<>();
                    handoff.put("fromAgent", "supervisor-agent");
                    handoff.put("toAgent", domain + "-agent");
                    handoff.put("handoffType", "parallel_invoke");
                    handoff.put("domain", domain);
                    handoff.put("traceId", traceId);
                    handoff.put("timestamp", System.currentTimeMillis());
                    return handoff;
                })
                .toList();
    }
}
