package com.bkanent.agent.client;

import com.bkanent.common.agent.AgentTaskInvokeRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialA2aMetadataMapperTest {

    @Test
    void mapsInternalInvocationContextIntoOfficialMetadataNamespace() {
        AgentTaskInvokeRequest request = new AgentTaskInvokeRequest(
                "session-1", "task-1", "parent-1", "trace-1", "supervisor-agent", "listing-agent",
                "listing.search", "listing", "查找房源", Map.of("keyword", "浦东", "topK", 5),
                List.of("artifact-1"), List.of("仅返回可售房源"), "json", "idem-1", true);

        Map<String, Object> metadata = OfficialA2aMetadataMapper.toMetadata(request, true);

        assertThat(metadata)
                .containsEntry(OfficialA2aMetadataMapper.THREAD_ID_KEY, "task-1")
                .containsEntry(OfficialA2aMetadataMapper.STREAMING_KEY, true);
        assertThat(metadata).containsKey(OfficialA2aMetadataMapper.SUPERVISOR_METADATA_KEY);
        @SuppressWarnings("unchecked")
        Map<String, Object> supervisor = (Map<String, Object>) metadata.get(
                OfficialA2aMetadataMapper.SUPERVISOR_METADATA_KEY);
        assertThat(supervisor)
                .containsEntry("version", "1")
                .containsEntry("sessionId", "session-1")
                .containsEntry("parentTaskId", "parent-1")
                .containsEntry("traceId", "trace-1")
                .containsEntry("structuredContext", Map.of("keyword", "浦东", "topK", 5));
        assertThat(supervisor.get("artifactIds")).isEqualTo(List.of("artifact-1"));
        assertThat(supervisor.get("constraints")).isEqualTo(List.of("仅返回可售房源"));
    }

    @Test
    void skillHintInStructuredContextIsHoistedToSupervisorNamespace() {
        AgentTaskInvokeRequest request = new AgentTaskInvokeRequest(
                "session-1", "task-1", "parent-1", "trace-1", "supervisor-agent", "contract-agent",
                "contract.risk_review", "contract", "审查合同 101",
                Map.of("skillHint", "contract-risk-review"),
                List.of(), List.of(), "json", "idem-1", true);

        Map<String, Object> metadata = OfficialA2aMetadataMapper.toMetadata(request, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> supervisor = (Map<String, Object>) metadata.get(
                OfficialA2aMetadataMapper.SUPERVISOR_METADATA_KEY);
        assertThat(supervisor.get(OfficialA2aMetadataMapper.SKILL_HINT_KEY))
                .isEqualTo("contract-risk-review");
        // structuredContext 保持原样透传
        assertThat(supervisor.get("structuredContext"))
                .isEqualTo(Map.of("skillHint", "contract-risk-review"));
    }

    @Test
    void noHintMeansNoSkillHintKey() {
        AgentTaskInvokeRequest request = new AgentTaskInvokeRequest(
                "session-1", "task-1", "parent-1", "trace-1", "supervisor-agent", "contract-agent",
                "contract.risk_review", "contract", "审查合同 101",
                null, List.of(), List.of(), "json", "idem-1", true);

        Map<String, Object> metadata = OfficialA2aMetadataMapper.toMetadata(request, false);

        @SuppressWarnings("unchecked")
        Map<String, Object> supervisor = (Map<String, Object>) metadata.get(
                OfficialA2aMetadataMapper.SUPERVISOR_METADATA_KEY);
        assertThat(supervisor.containsKey(OfficialA2aMetadataMapper.SKILL_HINT_KEY)).isFalse();
    }
}
