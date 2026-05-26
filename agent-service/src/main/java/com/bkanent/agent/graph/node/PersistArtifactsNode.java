package com.bkanent.agent.graph.node;

import com.bkanent.agent.workflow.TaskArtifactStore;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.agent.stream.SessionStreamService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PersistArtifactsNode {

    private final TaskArtifactStore taskArtifactStore;
    private final SessionStreamService sessionStreamService;

    public PersistArtifactsNode(TaskArtifactStore taskArtifactStore,
                                SessionStreamService sessionStreamService) {
        this.taskArtifactStore = taskArtifactStore;
        this.sessionStreamService = sessionStreamService;
    }

    public List<String> persistSingle(String taskId,
                                      String sessionId,
                                      String agentId,
                                      String userId,
                                      String traceId,
                                      AgentTaskInvokeResponse response) {
        List<String> baseArtifactIds = response == null || response.artifactIds() == null ? List.of() : response.artifactIds();
        if (response == null || response.structuredOutput() == null || response.structuredOutput().isEmpty()) {
            return baseArtifactIds;
        }
        String artifactType = response.structuredOutput().get("contentType") == null
                ? "agent_output"
                : String.valueOf(response.structuredOutput().get("contentType"));
        Integer versionNo = resolveArtifactVersion(response.structuredOutput());
        String artifactId = taskArtifactStore.save(
                taskId,
                sessionId,
                agentId,
                artifactType,
                versionNo,
                response.structuredOutput(),
                Map.of("summary", response.summary() == null ? "" : response.summary()),
                traceId
        );
        publishArtifactCreated(sessionId, taskId, agentId, userId, traceId, artifactId, artifactType, versionNo);
        List<String> merged = new ArrayList<>(baseArtifactIds);
        merged.add(artifactId);
        merged.addAll(persistDerivedArtifacts(
                taskId,
                sessionId,
                agentId,
                userId,
                traceId,
                artifactType,
                versionNo,
                response.structuredOutput(),
                response.summary()
        ));
        return List.copyOf(merged);
    }

    private List<String> persistDerivedArtifacts(String taskId,
                                                 String sessionId,
                                                 String agentId,
                                                 String userId,
                                                 String traceId,
                                                 String artifactType,
                                                 Integer versionNo,
                                                 Map<String, Object> structuredOutput,
                                                 String summary) {
        List<String> derivedArtifactIds = new ArrayList<>();
        if ("publish_payload".equalsIgnoreCase(artifactType)) {
            Object payload = structuredOutput.get("publishPayload");
            if (payload instanceof Map<?, ?> payloadMap && !payloadMap.isEmpty()) {
                String artifactId = saveDerivedArtifact(
                        taskId,
                        sessionId,
                        agentId,
                        userId,
                        traceId,
                        "publish_payload_body",
                        versionNo,
                        payloadMap,
                        summary
                );
                derivedArtifactIds.add(artifactId);
            }
        }
        if ("video_task".equalsIgnoreCase(artifactType)) {
            Map<String, Object> mediaTaskDetail = extractMediaTaskDetail(structuredOutput);
            if (!mediaTaskDetail.isEmpty()) {
                String artifactId = saveDerivedArtifact(
                        taskId,
                        sessionId,
                        agentId,
                        userId,
                        traceId,
                        "media_task_detail",
                        versionNo,
                        mediaTaskDetail,
                        summary
                );
                derivedArtifactIds.add(artifactId);
            }
        }
        if ("copy_draft".equalsIgnoreCase(artifactType)) {
            Object draftText = structuredOutput.get("draftText");
            if (draftText != null) {
                String artifactId = saveDerivedArtifact(
                        taskId,
                        sessionId,
                        agentId,
                        userId,
                        traceId,
                        "copy_draft_body",
                        versionNo,
                        Map.of(
                                "platform", structuredOutput.getOrDefault("platform", ""),
                                "draftText", draftText,
                                "appliedFeedback", structuredOutput.getOrDefault("appliedFeedback", "")
                        ),
                        summary
                );
                derivedArtifactIds.add(artifactId);
            }
        }
        if ("contract_summary".equalsIgnoreCase(artifactType)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            copyIfPresent(structuredOutput, detail, "contractStatus");
            copyIfPresent(structuredOutput, detail, "attachmentCount");
            copyIfPresent(structuredOutput, detail, "risks");
            copyIfPresent(structuredOutput, detail, "clauses");
            if (!detail.isEmpty()) {
                String artifactId = saveDerivedArtifact(
                        taskId,
                        sessionId,
                        agentId,
                        userId,
                        traceId,
                        "contract_review_detail",
                        versionNo,
                        detail,
                        summary
                );
                derivedArtifactIds.add(artifactId);
            }
        }
        if ("settlement_summary".equalsIgnoreCase(artifactType)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            copyIfPresent(structuredOutput, detail, "settlementStatus");
            copyIfPresent(structuredOutput, detail, "detail");
            copyIfPresent(structuredOutput, detail, "monthlySummary");
            copyIfPresent(structuredOutput, detail, "nextAction");
            if (!detail.isEmpty()) {
                String artifactId = saveDerivedArtifact(
                        taskId,
                        sessionId,
                        agentId,
                        userId,
                        traceId,
                        "settlement_detail_body",
                        versionNo,
                        detail,
                        summary
                );
                derivedArtifactIds.add(artifactId);
            }
        }
        return derivedArtifactIds;
    }

    private String saveDerivedArtifact(String taskId,
                                       String sessionId,
                                       String agentId,
                                       String userId,
                                       String traceId,
                                       String artifactType,
                                       Integer versionNo,
                                       Object content,
                                       String summary) {
        String artifactId = taskArtifactStore.save(
                taskId,
                sessionId,
                agentId,
                artifactType,
                versionNo,
                content,
                Map.of("summary", summary == null ? "" : summary),
                traceId
        );
        publishArtifactCreated(sessionId, taskId, agentId, userId, traceId, artifactId, artifactType, versionNo);
        return artifactId;
    }

    private Map<String, Object> extractMediaTaskDetail(Map<String, Object> structuredOutput) {
        Map<String, Object> detail = new LinkedHashMap<>();
        copyIfPresent(structuredOutput, detail, "mediaTaskId");
        copyIfPresent(structuredOutput, detail, "prompt");
        copyIfPresent(structuredOutput, detail, "status");
        copyIfPresent(structuredOutput, detail, "publishReady");
        copyIfPresent(structuredOutput, detail, "coverImageUrl");
        copyIfPresent(structuredOutput, detail, "videoUrl");
        copyIfPresent(structuredOutput, detail, "generatedAt");
        return detail;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private Integer resolveArtifactVersion(Map<String, Object> structuredOutput) {
        Object version = structuredOutput.get("version");
        if (version instanceof Number number) {
            return number.intValue();
        }
        Object retryCount = structuredOutput.get("retryCount");
        if (retryCount instanceof Number number) {
            return number.intValue() + 1;
        }
        return 1;
    }

    private void publishArtifactCreated(String sessionId,
                                        String taskId,
                                        String agentId,
                                        String userId,
                                        String traceId,
                                        String artifactId,
                                        String artifactType,
                                        Integer versionNo) {
        sessionStreamService.publish(new SessionStreamEvent(
                sessionId,
                taskId,
                agentId,
                "artifact.created",
                "Artifact persisted",
                Map.of(
                        "artifactId", artifactId,
                        "artifactType", artifactType,
                        "version", versionNo,
                        "userId", userId == null ? "" : userId
                ),
                traceId,
                System.currentTimeMillis()
        ));
    }
}
