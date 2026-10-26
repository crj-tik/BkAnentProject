package com.bkanent.agent.client;

import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.DurableMemoryExportDirective;
import com.bkanent.common.memory.MemoryScope;
import com.bkanent.common.memory.MemoryShareGrant;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialA2aMetadataMapperTest {

    @Test
    void explicitChildSelectionAndParentFactsRemainSeparateOnTheWire() {
        var parent = Map.of("name", "parent-flow", "owner", "supervisor", "version", "1");
        var request = new AgentTaskInvokeRequest("session", "child", "run", "trace", "supervisor-agent", "listing-agent",
                null, null, "原始子任务", Map.of("parentSkill", parent, "skillHint", "legacy-hint", "userId", "1"),
                List.of(), List.of(), "json", "call", false,
                new com.bkanent.common.agent.SkillSelection("listing-search", "2", "hash", "listing"));
        var supervisor = (Map<?, ?>) OfficialA2aMetadataMapper.toMetadata(request, false).get("supervisor");
        assertThat(supervisor.get("parentRunId")).isEqualTo("run");
        assertThat(supervisor.get("callId")).isEqualTo("call");
        assertThat(supervisor.get("parentSkill")).isEqualTo(parent);
        assertThat(supervisor.get("skillHint")).isEqualTo("legacy-hint");
        assertThat(supervisor.get("skillSelection")).isEqualTo(Map.of("name", "listing-search", "version", "2",
                "contentHash", "hash", "owner", "listing", "mode", "explicit"));
        assertThat(supervisor.containsKey("stepId")).isFalse();
    }

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
    void versionedAuthorizationRemovesModelSuppliedIdentityClaimsFromToolMetadata() {
        AgentTaskInvokeRequest request = new AgentTaskInvokeRequest(
                "session-1", "task-1", "parent-1", "trace-1", "supervisor-agent", "listing-agent",
                "listing.search", "listing", "查找房源",
                Map.ofEntries(Map.entry("keyword", "浦东"), Map.entry("userId", "999"),
                        Map.entry("actor_id", "999"), Map.entry("roles", List.of("ADMIN")),
                        Map.entry("isAdmin", true), Map.entry("token", "model-token"),
                        Map.entry("user", Map.of("id", "999"))),
                List.of(), List.of(), "json", "idem-1", true);

        Map<String, Object> metadata = OfficialA2aMetadataMapper.toMetadata(request, false, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> supervisor = (Map<String, Object>) metadata.get(
                OfficialA2aMetadataMapper.SUPERVISOR_METADATA_KEY);
        assertThat(supervisor.get("structuredContext")).isEqualTo(Map.of("keyword", "浦东"));
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

    @Test
    void carriesOwnerAuthorizedExportDirectiveOnlyInSupervisorNamespace() {
        Instant from = Instant.parse("2026-10-21T00:00:00Z");
        DurableMemoryExportDirective directive = new DurableMemoryExportDirective("grant-1",
                new MemoryScope(MemoryScope.Kind.SESSION_SHARED, null, null, "session-1"), null,
                "listing-review", List.of(new MemoryShareGrant.Participant("listing", "listing-agent", "conversation-1")),
                List.of(MemoryShareGrant.Projection.DURABLE_EXPORT), List.of("result"), false,
                from, from.plusSeconds(3600));
        AgentTaskInvokeRequest request = new AgentTaskInvokeRequest(
                "session-1", "task-1", "parent-1", "trace-1", "supervisor-agent", "listing-agent",
                "listing.search", "listing", "查找房源", Map.of("keyword", "浦东"),
                List.of(), List.of(), "json", "idem-1", true, null, directive);

        Map<String, Object> metadata = OfficialA2aMetadataMapper.toMetadata(request, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> supervisor = (Map<String, Object>) metadata.get("supervisor");

        assertThat(supervisor.get("memoryExport")).isEqualTo(directive);
        assertThat(((Map<?, ?>) supervisor.get("structuredContext")).containsKey("memoryExport")).isFalse();
        assertThat(metadata).doesNotContainKey("memoryExport");
    }
}
