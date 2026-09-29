package com.bkanent.agent.service;

import com.bkanent.agent.catalog.DomainCatalog;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.model.distributed.WorkflowPlan;
import com.bkanent.agent.registry.AgentDescriptorSource;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.AgentRuntimeType;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowPlanValidatorTest {

    private final AgentRegistry registry = mock(AgentRegistry.class);
    private final DomainCatalog catalog = mock(DomainCatalog.class);
    private final DistributedAgentProperties properties = new DistributedAgentProperties();
    private final WorkflowPlanValidator validator = new WorkflowPlanValidator(registry, catalog, properties);

    @Test
    void domainOutsideCatalogMustBeRejected() {
        when(catalog.contains(anyString())).thenReturn(false);

        assertThatThrownBy(() -> validator.validate(plan("marketing", "marketing.generate_copy")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domain is invalid");
    }

    @Test
    void domainInsideCatalogMustBeAccepted() {
        when(catalog.contains("marketing")).thenReturn(true);

        WorkflowPlan validated = validator.validate(plan("marketing", "marketing.generate_copy"));

        assertThat(validated.domain()).isEqualTo("marketing");
    }

    @Test
    void derivedApprovalWorkflowTypeMustBeAcceptedForCatalogDomain() {
        when(catalog.contains("compare")).thenReturn(true);

        WorkflowPlan plan = new WorkflowPlan(
                "compare.listings", "compare", "compare_with_approval", true,
                List.of(), null, List.of(), "deepseek-chat", "{}");

        assertThat(validator.validate(plan).workflowType()).isEqualTo("compare_with_approval");
    }

    @Test
    void derivedApprovalWorkflowTypeMustBeRejectedForUnknownPrefix() {
        when(catalog.contains("listing")).thenReturn(true);
        when(catalog.contains("ghost")).thenReturn(false);

        WorkflowPlan plan = new WorkflowPlan(
                "listing.search", "listing", "ghost_with_approval", true,
                List.of(), null, List.of(), "deepseek-chat", "{}");

        assertThatThrownBy(() -> validator.validate(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workflowType is invalid");
    }

    @Test
    void parallelDomainsMustRespectConfiguredMaxParallelDomains() {
        when(catalog.contains(anyString())).thenReturn(true);
        properties.getPlanning().setMaxParallelDomains(2);

        WorkflowPlan plan = new WorkflowPlan(
                "x", "listing", "parallel", false,
                List.of("listing", "marketing", "media"), null, List.of(), "deepseek-chat", "{}");

        assertThatThrownBy(() -> validator.validate(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds the maximum of 2");
    }

    @Test
    void approvalWorkflowTypeMustRequireApprovalFlag() {
        when(catalog.contains("listing")).thenReturn(true);

        WorkflowPlan plan = new WorkflowPlan(
                "listing.search", "listing", "listing_with_approval", false,
                List.of(), null, List.of(), "deepseek-chat", "{}");

        assertThatThrownBy(() -> validator.validate(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must require approval");
    }

    @Test
    void selectedAgentMustSupportPlanDomain() {
        when(catalog.contains("marketing")).thenReturn(true);
        when(registry.getByAgentId("compare-agent")).thenReturn(Optional.of(
                descriptor("compare-agent", List.of("compare"))));

        WorkflowPlan plan = new WorkflowPlan(
                "marketing.generate_copy", "marketing", "single_agent", null,
                List.of(), "compare-agent", List.of(), "deepseek-chat", "{}");

        assertThatThrownBy(() -> validator.validate(plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support the plan domain");
    }

    private WorkflowPlan plan(String domain, String intent) {
        return new WorkflowPlan(intent, domain, "single_agent", null,
                List.of(), null, List.of(), "deepseek-chat", "{}");
    }

    private RegisteredAgentDescriptor descriptor(String agentId, List<String> domains) {
        return new RegisteredAgentDescriptor(agentId, "http://localhost", "/.well-known/agent.json",
                "/a2a", AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.DISCOVERED_CARD,
                new AgentCard(agentId, agentId, agentId, "1.0.0", List.of(), domains, false, false,
                        "http://localhost/a2a", List.of("text"), List.of("text")),
                Map.of());
    }
}
