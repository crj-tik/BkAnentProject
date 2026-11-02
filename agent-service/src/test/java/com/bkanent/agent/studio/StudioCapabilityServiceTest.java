package com.bkanent.agent.studio;

import com.bkanent.agent.registry.AgentRegistry;
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
                .doesNotContain("contracts", "settlements");
        assertThat(capabilities).allSatisfy(item -> assertThat(item.get("directOperations")).isEqualTo(List.of()));
    }
}
