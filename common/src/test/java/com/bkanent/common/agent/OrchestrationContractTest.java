package com.bkanent.common.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oldPayloadAndConstructorHaveNoImplicitSelection() throws Exception {
        AgentTaskInvokeRequest request = new AgentTaskInvokeRequest("s", "t", null, "trace", "supervisor",
                "listing", null, null, "find listings", Map.of("skillHint", "hint-only"), List.of(),
                List.of(), null, "key", false);
        assertThat(request.skillSelection()).isNull();
        assertThat(OrchestrationMode.from(request.skillSelection())).isEqualTo(OrchestrationMode.AUTO);
        AgentTaskInvokeRequest restored = mapper.readValue("{\"sessionId\":\"s\",\"instruction\":\"hello\"}",
                AgentTaskInvokeRequest.class);
        assertThat(restored.skillSelection()).isNull();
        assertThat(restored.instruction()).isEqualTo("hello");
    }

    @Test
    void preservesExplicitContentIdentityAcrossSerialization() throws Exception {
        SkillSelection selection = new SkillSelection("listing-search", "1", "sha256-example", "listing-agent");
        assertThat(mapper.readValue(mapper.writeValueAsString(selection), SkillSelection.class)).isEqualTo(selection);
        assertThat(OrchestrationMode.from(selection)).isEqualTo(OrchestrationMode.EXPLICIT_SKILL);
        assertThat(new SkillSelection(" listing-search ", " ").version()).isNull();
        assertThatThrownBy(() -> new SkillSelection(" ", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void separatesProtocolsConnectionsAndEscapedSegments() throws Exception {
        assertThat(CapabilityId.mcp("one", "search")).isNotEqualTo(CapabilityId.mcp("two", "search"));
        assertThat(CapabilityId.local("search")).isNotEqualTo(CapabilityId.a2a("search"));
        assertThat(CapabilityId.mcp("one:two", "search").value()).isEqualTo("mcp:one%3Atwo:search");
        assertThat(CapabilityId.mcp("one%3Atwo", "search")).isNotEqualTo(CapabilityId.mcp("one:two", "search"));
        CapabilityId identity = CapabilityId.a2a("listing-agent");
        assertThat(mapper.readValue(mapper.writeValueAsString(identity), CapabilityId.class)).isEqualTo(identity);
        assertThatThrownBy(() -> new CapabilityId("mcp:one:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityId("unknown:tool")).isInstanceOf(IllegalArgumentException.class);
    }
}
