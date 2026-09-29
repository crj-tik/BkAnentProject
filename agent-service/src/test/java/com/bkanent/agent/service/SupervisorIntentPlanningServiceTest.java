package com.bkanent.agent.service;

import com.bkanent.agent.catalog.DomainCatalog;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.common.agent.AgentCard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisorIntentPlanningServiceTest {

    private final AgentChatService agentChatService = mock(AgentChatService.class);
    private final WorkflowPlanValidator validator = mock(WorkflowPlanValidator.class);
    private final DomainCatalog catalog = mock(DomainCatalog.class);
    private final DistributedAgentProperties properties = new DistributedAgentProperties();
    private final SupervisorIntentPlanningService service = new SupervisorIntentPlanningService(
            properties, agentChatService, validator, catalog, new ObjectMapper());

    @Test
    void systemPromptMustInjectCatalogDomainsWithDescriptionsAndSkills() {
        properties.getPlanning().setLlmEnabled(true);
        properties.getPlanning().setStrategy("llm-first");
        when(catalog.snapshot()).thenReturn(new DomainCatalog.CatalogSnapshot(
                List.of("compare", "listing"),
                List.of(card("compare-agent", List.of("compare"), "multi-listing comparison agent",
                        List.of("compare.listings"))),
                DomainCatalog.SOURCE_REGISTRY));
        when(agentChatService.getModel()).thenReturn("deepseek-chat");
        when(agentChatService.call(anyString(), anyString(), anyBoolean())).thenReturn(
                "{\"intent\":\"compare.listings\",\"domain\":\"compare\",\"workflowType\":\"single_agent\"}");
        when(validator.validate(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.tryPlan("对比这几套房源", Map.of());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentChatService).call(promptCaptor.capture(), anyString(), anyBoolean());
        String systemPrompt = promptCaptor.getValue();
        assertThat(systemPrompt)
                .contains("Allowed domains")
                .contains("- compare: multi-listing comparison agent (skills: compare.listings)")
                .contains("- listing")
                .contains("<domain>_with_approval");
        assertThat(systemPrompt).doesNotContain("Allowed domains: listing, marketing, media");
    }

    @Test
    void tryPlanMustReturnNullWhenLlmPlanningDisabled() {
        assertThat(service.tryPlan("query", Map.of())).isNull();
    }

    private AgentCard card(String agentId, List<String> domains, String description, List<String> skills) {
        return new AgentCard(agentId, agentId, description, "1.0.0", skills, domains,
                false, false, "http://localhost/a2a", List.of("text"), List.of("text"));
    }
}
