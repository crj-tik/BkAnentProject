package com.bkanent.agent.graph.official;

import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialCheckpointMigrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void migratesLegacyWorkflowStateIntoApprovalCheckpoint() throws Exception {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("sessionId", "session-1");
        legacy.put("taskId", "task-1");
        legacy.put("traceId", "trace-1");
        legacy.put("userId", "user-1");
        legacy.put("userMessage", "需要审批");
        legacy.put("workflowStatus", "WAITING_USER_APPROVAL");
        legacy.put("pendingApproval", Map.of(
                "approvalId", "approval-1",
                "subjectVersion", 2,
                "approveNextNode", "single_agent"
        ));

        OfficialCheckpointMigration.ReadResult result = OfficialCheckpointMigration.read(
                objectMapper.writeValueAsString(legacy),
                "official-supervisor", "legacy-row-1", objectMapper);

        assertThat(result.migrated()).isTrue();
        Checkpoint checkpoint = result.checkpoint();
        assertThat(checkpoint).isNotNull();
        assertThat(checkpoint.getNodeId()).isEqualTo(OfficialSupervisorGraphNodeNames.APPROVAL_GATE);
        assertThat(checkpoint.getState())
                .containsEntry(OfficialSupervisorGraphKeys.APPROVAL_VERSION, 2)
                .containsEntry(OfficialSupervisorGraphKeys.REQUIRE_APPROVAL, true);
    }

    @Test
    void migratesEnvelopeWithoutGraphNameAndRejectsAnotherGraph() throws Exception {
        Map<String, Object> state = Map.of(
                OfficialSupervisorGraphKeys.TASK_ID, "task-2",
                OfficialSupervisorGraphKeys.SESSION_ID, "session-2",
                OfficialSupervisorGraphKeys.WORKFLOW_STATUS, "RUNNING"
        );
        String oldEnvelope = objectMapper.writeValueAsString(Map.of(
                "version", 1,
                "id", "checkpoint-2",
                "nodeId", OfficialSupervisorGraphNodeNames.ROUTE,
                "nextNodeId", OfficialSupervisorGraphNodeNames.SINGLE_AGENT,
                "state", state
        ));

        OfficialCheckpointMigration.ReadResult migrated = OfficialCheckpointMigration.read(
                oldEnvelope, "official-supervisor", "legacy-row-2", objectMapper);
        OfficialCheckpointMigration.ReadResult ignored = OfficialCheckpointMigration.read(
                oldEnvelope, "official-planning", "legacy-row-2", objectMapper);

        assertThat(migrated.migrated()).isTrue();
        assertThat(migrated.checkpoint().getState())
                .containsEntry(OfficialSupervisorGraphKeys.NEXT_NODE,
                        OfficialSupervisorGraphNodeNames.ROUTE);
        assertThat(ignored.checkpoint()).isNull();
    }
}
