package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads checkpoint formats written before the official top-level Graph became
 * the source of truth and upgrades them to the current state shape.
 */
public final class OfficialCheckpointMigration {

    private static final Set<String> SUPERVISOR_NODES = Set.of(
            OfficialSupervisorGraphNodeNames.PLAN,
            OfficialSupervisorGraphNodeNames.ROUTE,
            OfficialSupervisorGraphNodeNames.APPROVAL_GATE,
            OfficialSupervisorGraphNodeNames.RESUME_DECISION,
            OfficialSupervisorGraphNodeNames.SINGLE_AGENT,
            OfficialSupervisorGraphNodeNames.PARALLEL_AGENTS,
            OfficialSupervisorGraphNodeNames.PARALLEL_FAN_OUT,
            OfficialSupervisorGraphNodeNames.PARALLEL_AGGREGATE,
            OfficialSupervisorGraphNodeNames.REGENERATE,
            OfficialSupervisorGraphNodeNames.COMPLETE,
            OfficialSupervisorGraphNodeNames.CANCEL,
            OfficialSupervisorGraphNodeNames.FAIL
    );
    private static final Set<String> PLANNING_NODES = Set.of(
            OfficialGraphNodeNames.LOAD_SESSION,
            "skill_match",
            OfficialGraphNodeNames.LLM_INTENT_PLAN,
            OfficialGraphNodeNames.PLAN_VALIDATION,
            OfficialGraphNodeNames.PARSE_INTENT,
            OfficialGraphNodeNames.PLAN_TASK,
            OfficialGraphNodeNames.SELECT_AGENT
    );

    private OfficialCheckpointMigration() {
    }

    public static ReadResult read(String snapshotJson,
                                  String graphName,
                                  String sourceId,
                                  ObjectMapper objectMapper) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return ReadResult.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            if (root.has("state") && root.path("state").isObject()
                    && root.has("nodeId")) {
                String storedGraphName = text(root, "graphName");
                String nodeId = text(root, "nodeId");
                if (storedGraphName != null && !graphName.equals(storedGraphName)) {
                    return ReadResult.empty();
                }
                if (storedGraphName == null && !belongsToGraph(graphName, nodeId)) {
                    return ReadResult.empty();
                }
                Map<String, Object> state = objectMapper.convertValue(
                        root.path("state"), Map.class);
                Map<String, Object> normalized = normalizeState(state);
                Checkpoint checkpoint = checkpoint(
                        text(root, "id"), nodeId, text(root, "nextNodeId"), normalized);
                return new ReadResult(checkpoint, storedGraphName == null,
                        storedGraphName == null ? sourceId : null);
            }

            if (!"official-supervisor".equals(graphName) || !root.isObject()
                    || !root.has(OfficialSupervisorGraphKeys.TASK_ID)) {
                return ReadResult.empty();
            }
            Map<String, Object> legacyState = objectMapper.convertValue(root, Map.class);
            Map<String, Object> normalized = normalizeState(legacyState);
            String nodeId = inferCurrentNode(normalized);
            Checkpoint checkpoint = checkpoint(sourceId, nodeId,
                    inferNextNode(normalized), normalized);
            return new ReadResult(checkpoint, true, sourceId);
        } catch (Exception ignored) {
            return ReadResult.empty();
        }
    }

    public static String migratedFromId(String snapshotJson,
                                        ObjectMapper objectMapper) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            return null;
        }
        try {
            return text(objectMapper.readTree(snapshotJson), "migratedFromId");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Checkpoint checkpoint(String id,
                                        String nodeId,
                                        String nextNodeId,
                                        Map<String, Object> state) {
        return Checkpoint.builder()
                .id(id)
                .nodeId(nodeId)
                .nextNodeId(nextNodeId)
                .state(state)
                .build();
    }

    private static Map<String, Object> normalizeState(Map<String, Object> source) {
        Map<String, Object> state = new LinkedHashMap<>(source);
        state.putIfAbsent(OfficialSupervisorGraphKeys.WORKFLOW_STATUS, "RUNNING");
        state.putIfAbsent(OfficialSupervisorGraphKeys.SHARED_CONTEXT, Map.of());
        state.putIfAbsent(OfficialSupervisorGraphKeys.PARALLEL_DOMAINS, List.of());
        state.putIfAbsent(OfficialSupervisorGraphKeys.ARTIFACT_IDS, List.of());
        state.putIfAbsent(OfficialSupervisorGraphKeys.HANDOFF_HISTORY, List.of());
        state.putIfAbsent(OfficialSupervisorGraphKeys.EVENT_REFERENCES, List.of());
        state.putIfAbsent(OfficialSupervisorGraphKeys.PARALLEL_BRANCH_RESULTS, List.of());
        state.putIfAbsent(OfficialSupervisorGraphKeys.RETRY_COUNT, 0);
        state.putIfAbsent(OfficialSupervisorGraphKeys.MAX_RETRY_COUNT, 3);
        state.putIfAbsent(OfficialSupervisorGraphKeys.PLAN_VERSION, 1);
        state.putIfAbsent(OfficialSupervisorGraphKeys.APPROVAL_VERSION,
                approvalVersion(state.get(OfficialSupervisorGraphKeys.PENDING_APPROVAL)));
        state.putIfAbsent(OfficialSupervisorGraphKeys.PARALLEL_AGGREGATION_STRATEGY, "ALL_OF");
        state.putIfAbsent(OfficialSupervisorGraphKeys.CURRENT_NODE, inferCurrentNode(state));
        state.putIfAbsent(OfficialSupervisorGraphKeys.NEXT_NODE, inferNextNode(state));
        state.putIfAbsent(OfficialSupervisorGraphKeys.REQUIRE_PARALLEL,
                castList(state.get(OfficialSupervisorGraphKeys.PARALLEL_DOMAINS)).size() > 1);
        state.putIfAbsent(OfficialSupervisorGraphKeys.REQUIRE_APPROVAL,
                state.get(OfficialSupervisorGraphKeys.PENDING_APPROVAL) != null);
        return state;
    }

    private static String inferCurrentNode(Map<String, Object> state) {
        String status = String.valueOf(state.getOrDefault(
                OfficialSupervisorGraphKeys.WORKFLOW_STATUS, "RUNNING"));
        if ("WAITING_USER_APPROVAL".equals(status)) {
            return OfficialSupervisorGraphNodeNames.APPROVAL_GATE;
        }
        if ("COMPLETED".equals(status)) {
            return OfficialSupervisorGraphNodeNames.COMPLETE;
        }
        if ("CANCELED".equals(status)) {
            return OfficialSupervisorGraphNodeNames.CANCEL;
        }
        if ("FAILED".equals(status)) {
            return OfficialSupervisorGraphNodeNames.FAIL;
        }
        return OfficialSupervisorGraphNodeNames.ROUTE;
    }

    private static String inferNextNode(Map<String, Object> state) {
        Object pending = state.get(OfficialSupervisorGraphKeys.PENDING_APPROVAL);
        if (pending instanceof Map<?, ?> map) {
            Object next = map.get("approveNextNode");
            if (next != null) {
                return String.valueOf(next);
            }
        }
        return inferCurrentNode(state);
    }

    private static int approvalVersion(Object value) {
        if (value instanceof Map<?, ?> map && map.get("subjectVersion") instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private static boolean belongsToGraph(String graphName, String nodeId) {
        if ("official-supervisor".equals(graphName)) {
            return SUPERVISOR_NODES.contains(nodeId);
        }
        if ("official-supervisor-planning".equals(graphName)) {
            return PLANNING_NODES.contains(nodeId);
        }
        if ("official-supervisor-parallel".equals(graphName)) {
            return Set.of(
                    OfficialParallelGraphNodeNames.PARALLEL_INVOKE,
                    OfficialParallelGraphNodeNames.PERSIST_PARALLEL_ARTIFACTS,
                    OfficialParallelGraphNodeNames.MERGE_PARALLEL_RESULT
            ).contains(nodeId);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public record ReadResult(Checkpoint checkpoint,
                             boolean migrated,
                             String migratedFromId) {
        public static ReadResult empty() {
            return new ReadResult(null, false, null);
        }
    }
}
