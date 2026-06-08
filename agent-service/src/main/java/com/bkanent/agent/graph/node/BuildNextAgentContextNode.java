package com.bkanent.agent.graph.node;

import com.bkanent.agent.service.WorkflowHistoryService;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.WorkflowHistoryView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BuildNextAgentContextNode {

    private static final Logger log = LoggerFactory.getLogger(BuildNextAgentContextNode.class);

    private final WorkflowHistoryService workflowHistoryService;

    public BuildNextAgentContextNode(WorkflowHistoryService workflowHistoryService) {
        this.workflowHistoryService = workflowHistoryService;
    }

    public Map<String, Object> build(SupervisorWorkflowState state, String nextDomain) {
        Map<String, Object> context = new LinkedHashMap<>(state.sharedContext());
        context.put("domain", nextDomain);

        // 注入工作流历史
        try {
            WorkflowHistoryView history = workflowHistoryService.assembleHistory(state.taskId());
            if (history.steps() != null && !history.steps().isEmpty()) {
                context.put("workflowHistory", history.steps());
            }
            if (history.artifactSummaries() != null && !history.artifactSummaries().isEmpty()) {
                context.put("upstreamArtifactSummaries", history.artifactSummaries());
            }
        } catch (Exception e) {
            log.debug("Failed to assemble workflow history: {}", e.getMessage());
        }
        copyIfPresent(state.sharedContext(), context, "requestStream");
        copyIfPresent(state.sharedContext(), context, "forceAsyncA2a");
        copyIfPresent(state.sharedContext(), context, "latestArtifactIds");
        copyIfPresent(state.sharedContext(), context, "latestPrimaryArtifactId");
        copyIfPresent(state.sharedContext(), context, "latestArtifactType");
        copyIfPresent(state.sharedContext(), context, "artifactRefs");
        copyIfPresent(state.sharedContext(), context, "copyDraftArtifactId");
        copyIfPresent(state.sharedContext(), context, "copyDraftBodyArtifactId");
        copyIfPresent(state.sharedContext(), context, "publishPayloadArtifactId");
        copyIfPresent(state.sharedContext(), context, "publishPayloadBodyArtifactId");
        copyIfPresent(state.sharedContext(), context, "mediaTaskArtifactId");
        copyIfPresent(state.sharedContext(), context, "mediaTaskDetailArtifactId");
        copyIfPresent(state.sharedContext(), context, "contractSummaryArtifactId");
        copyIfPresent(state.sharedContext(), context, "contractReviewDetailArtifactId");
        copyIfPresent(state.sharedContext(), context, "settlementSummaryArtifactId");
        copyIfPresent(state.sharedContext(), context, "settlementDetailArtifactId");
        if (state.latestAgentResponse() != null && state.latestAgentResponse().structuredOutput() != null) {
            Map<String, Object> output = state.latestAgentResponse().structuredOutput();
            copyIfPresent(output, context, "listingOutput");
            copyIfPresent(output, context, "tradeOutput");
            copyIfPresent(output, context, "mergeSummary");
            copyIfPresent(output, context, "routeDecision");
            copyIfPresent(output, context, "listingCount");
            copyIfPresent(output, context, "mediaTaskId");
            copyIfPresent(output, context, "contentId");
            copyIfPresent(output, context, "title");
            copyIfPresent(output, context, "videoUrl");
            copyIfPresent(output, context, "coverImageUrl");
            copyIfPresent(output, context, "publishStatus");
            copyIfPresent(output, context, "externalPublishId");
            copyIfPresent(output, context, "notificationId");
            copyIfPresent(output, context, "contractStatus");
            copyIfPresent(output, context, "tradeDecision");
            copyIfPresent(output, context, "artifactTypeHint");
            copyIfPresent(output, context, "detailArtifactTypeHint");
            mergeCompactContentContext(context, output, nextDomain);
        }
        return context;
    }

    private void mergeCompactContentContext(Map<String, Object> context,
                                            Map<String, Object> output,
                                            String nextDomain) {
        if ("media".equalsIgnoreCase(nextDomain) || "marketing".equalsIgnoreCase(nextDomain)) {
            Object copywriting = output.get("copywriting");
            if (copywriting != null) {
                context.put("copywriting", copywriting);
            } else {
                copyIfPresent(output, context, "draftText", "copywriting");
            }
        }
        if ("settlement".equalsIgnoreCase(nextDomain)) {
            copyIfPresent(output, context, "nextAction");
            copyIfPresent(output, context, "settlementStatus");
        }
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null) {
            target.put(targetKey, value);
        }
    }
}
