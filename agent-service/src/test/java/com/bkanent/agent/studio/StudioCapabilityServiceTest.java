package com.bkanent.agent.studio;

import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.AgentDescriptorSource;
import com.bkanent.agent.registry.AgentRuntimeType;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StudioCapabilityServiceTest {
    @Test
    void retiredDomainsAreAbsentAndDiscoveryDoesNotGrantDirectOperations() {
        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.listDescriptors()).thenReturn(List.of());
        List<Map<String, Object>> capabilities = new StudioCapabilityService(registry).list(true);

        assertThat(capabilities).extracting(item -> item.get("id"))
                .contains("reviews")
                .doesNotContain("contracts", "settlements");
        assertThat(capabilities).allSatisfy(item -> assertThat(item.get("directOperations")).isEqualTo(List.of()));
        assertThat(capability(capabilities, "business").get("availability")).isEqualTo("UNKNOWN");
        assertThat(capability(capabilities, "business").get("authorization"))
                .isEqualTo(Map.of("status", "NOT_EVALUATED", "checkMode", "PER_OPERATION",
                        "reason", "具体用户权限和资源范围由目标业务接口逐请求校验"));
        assertThat(capability(capabilities, "admin").get("authorization"))
                .isEqualTo(Map.of("status", "AUTHORIZED", "checkMode", "PERMISSION_CHECKED",
                        "permission", "auth.users.manage"));
    }

    @Test
    void platformOperationsDiscoveryIsSplitIntoAnalyticsReviewsAndKnowledge() {
        AgentRegistry registry = mock(AgentRegistry.class);
        AgentCard card = new AgentCard("platform-operations-agent", "平台经营", "平台经营能力", "1.0.0",
                List.of(), List.of("platform-operations"), true, true, "http://platform-operations-service/a2a",
                List.of("text"), List.of("text"));
        RegisteredAgentDescriptor descriptor = new RegisteredAgentDescriptor("platform-operations-agent",
                "http://platform-operations-service", "/.well-known/agent.json", "/a2a",
                AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.STATIC_CONFIG, card, Map.of());
        when(registry.listDescriptors()).thenReturn(List.of(descriptor));

        List<Map<String, Object>> capabilities = new StudioCapabilityService(registry).list(false);

        assertThat(capability(capabilities, "business").get("availability")).isEqualTo("AVAILABLE");
        assertThat(capability(capabilities, "reviews").get("availability")).isEqualTo("AVAILABLE");
        assertThat(capability(capabilities, "knowledge").get("availability")).isEqualTo("AVAILABLE");
        assertThat(capability(capabilities, "interviews").get("availability")).isEqualTo("UNKNOWN");
        assertThat(capabilities).allSatisfy(item -> assertThat(item.get("directOperations")).isEqualTo(List.of()));
    }

    private Map<String, Object> capability(List<Map<String, Object>> capabilities, String id) {
        return capabilities.stream().filter(item -> id.equals(item.get("id"))).findFirst().orElseThrow();
    }
}
