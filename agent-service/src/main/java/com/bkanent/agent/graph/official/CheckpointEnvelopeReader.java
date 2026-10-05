package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/** Reads checkpoints only for their exact runner version; legacy graph snapshots are history-only. */
final class CheckpointEnvelopeReader {

    private CheckpointEnvelopeReader() {
    }

    public static Checkpoint read(String snapshotJson, String graphName, ObjectMapper objectMapper) {
        if (snapshotJson == null || snapshotJson.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(snapshotJson);
            if (root.path("version").asInt(-1) != 1
                    || !root.path("state").isObject() || !root.path("nodeId").isTextual()
                    || !graphName.equals(root.path("graphName").asText())) return null;
            Map<String, Object> state = objectMapper.convertValue(root.path("state"), Map.class);
            return Checkpoint.builder()
                    .id(root.path("id").asText())
                    .nodeId(root.path("nodeId").asText())
                    .nextNodeId(root.path("nextNodeId").asText())
                    .state(state)
                    .build();
        } catch (Exception ignored) {
            return null;
        }
    }
}
