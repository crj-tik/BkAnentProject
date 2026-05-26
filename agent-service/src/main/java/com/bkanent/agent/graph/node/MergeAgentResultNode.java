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
public class MergeAgentResultNode {

    public SupervisorWorkflowState mergeSingle(SupervisorGraphState graphState,
                                               String selectedAgentId,
                                               List<String> artifactIds,
                                               AgentTaskInvokeResponse response) {
        return new SupervisorWorkflowState(
                graphState.sessionId(),
                graphState.taskId(),
                graphState.traceId(),
                graphState.userId(),
                graphState.userMessage(),
                WorkflowStatus.RUNNING,
                selectedAgentId,
                enrichSharedContext(graphState.sharedContext(), artifactIds, response),
                List.of(buildHandoffEntry("supervisor-agent", selectedAgentId, "single_invoke", graphState.domain(), graphState.traceId())),
                artifactIds,
                response,
                null,
                null,
                null
        );
    }

    private Map<String, Object> buildHandoffEntry(String fromAgent,
                                                  String toAgent,
                                                  String handoffType,
                                                  String domain,
                                                  String traceId) {
        return Map.of(
                "fromAgent", fromAgent,
                "toAgent", toAgent,
                "handoffType", handoffType,
                "domain", domain,
                "traceId", traceId,
                "timestamp", System.currentTimeMillis()
        );
    }

    private Map<String, Object> enrichSharedContext(Map<String, Object> context,
                                                    List<String> artifactIds,
                                                    AgentTaskInvokeResponse response) {
        Map<String, Object> merged = new LinkedHashMap<>(context);
        merged.put("latestArtifactIds", artifactIds);
        if (response == null || response.structuredOutput() == null || artifactIds == null || artifactIds.isEmpty()) {
            return Map.copyOf(merged);
        }
        Map<String, Object> refs = buildArtifactRefs(response.structuredOutput(), artifactIds);
        if (!refs.isEmpty()) {
            merged.put("artifactRefs", refs);
            refs.forEach(merged::put);
        }
        return Map.copyOf(merged);
    }

    private Map<String, Object> buildArtifactRefs(Map<String, Object> output, List<String> artifactIds) {
        Map<String, Object> refs = new LinkedHashMap<>();
        if (artifactIds.isEmpty()) {
            return refs;
        }
        String primaryArtifactId = artifactIds.get(0);
        refs.put("latestPrimaryArtifactId", primaryArtifactId);
        refs.put("latestArtifactCount", artifactIds.size());
        putIfText(refs, "latestArtifactType", output.get("contentType"));
        putIfText(refs, "latestArtifactHintType", output.get("artifactTypeHint"));
        mapPrimaryArtifact(refs, output, primaryArtifactId);
        mapDerivedArtifact(refs, output, artifactIds, 1);
        return refs;
    }

    private void mapPrimaryArtifact(Map<String, Object> refs, Map<String, Object> output, String artifactId) {
        String contentType = asText(output.get("contentType"));
        if ("copy_draft".equalsIgnoreCase(contentType)) {
            refs.put("copyDraftArtifactId", artifactId);
        } else if ("publish_payload".equalsIgnoreCase(contentType)) {
            refs.put("publishPayloadArtifactId", artifactId);
        } else if ("video_task".equalsIgnoreCase(contentType)) {
            refs.put("mediaTaskArtifactId", artifactId);
        } else if ("contract_summary".equalsIgnoreCase(contentType)) {
            refs.put("contractSummaryArtifactId", artifactId);
        } else if ("settlement_summary".equalsIgnoreCase(contentType)) {
            refs.put("settlementSummaryArtifactId", artifactId);
        }
    }

    private void mapDerivedArtifact(Map<String, Object> refs,
                                    Map<String, Object> output,
                                    List<String> artifactIds,
                                    int startIndex) {
        int index = startIndex;
        String derivedHint = asText(output.get("artifactTypeHint"));
        if (derivedHint != null && index < artifactIds.size()) {
            refs.put(derivedKeyName(derivedHint), artifactIds.get(index++));
        }
        String detailHint = asText(output.get("detailArtifactTypeHint"));
        if (detailHint != null && index < artifactIds.size()) {
            refs.put(derivedKeyName(detailHint), artifactIds.get(index));
        }
    }

    private String derivedKeyName(String artifactType) {
        return switch (artifactType) {
            case "copy_draft_body" -> "copyDraftBodyArtifactId";
            case "publish_payload_body" -> "publishPayloadBodyArtifactId";
            case "media_task_detail" -> "mediaTaskDetailArtifactId";
            case "contract_review_detail" -> "contractReviewDetailArtifactId";
            case "settlement_detail_body" -> "settlementDetailArtifactId";
            default -> artifactType + "ArtifactId";
        };
    }

    private void putIfText(Map<String, Object> target, String key, Object value) {
        String text = asText(value);
        if (text != null) {
            target.put(key, text);
        }
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }
}
