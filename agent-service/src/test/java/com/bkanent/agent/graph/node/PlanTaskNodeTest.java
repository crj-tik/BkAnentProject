package com.bkanent.agent.graph.node;

import com.bkanent.agent.catalog.DomainCatalog;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.service.SupervisorIntentPlanningService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanTaskNodeTest {

    private final SupervisorIntentPlanningService planningService = mock(SupervisorIntentPlanningService.class);
    private final DomainCatalog catalog = mock(DomainCatalog.class);
    private final DistributedAgentProperties properties = new DistributedAgentProperties();
    private final PlanTaskNode node = new PlanTaskNode(planningService, catalog, properties);

    @Test
    void configuredParallelRuleMustProduceDomainsWhenAllGroupsMatch() {
        when(planningService.readPlan(any())).thenReturn(null);
        when(catalog.contains("listing")).thenReturn(true);
        when(catalog.contains("trade")).thenReturn(true);

        SupervisorGraphState state = node.apply(state(
                Map.of("requireParallel", true), "同时分析这套房源的交易风险"));

        assertThat(state.parallelDomains()).containsExactly("listing", "trade");
    }

    @Test
    void parallelRuleMustBeSkippedWhenDomainNotInCatalog() {
        properties.getCatalog().getRuleRouting().setParallelRules(List.of(
                new DistributedAgentProperties.ParallelRoutingRule(
                        List.of(List.of("房源"), List.of("交易")), List.of("listing", "ghost"))));
        when(planningService.readPlan(any())).thenReturn(null);
        when(catalog.contains("listing")).thenReturn(true);
        when(catalog.contains("ghost")).thenReturn(false);

        SupervisorGraphState state = node.apply(state(
                Map.of("requireParallel", true), "分析房源和交易"));

        assertThat(state.parallelDomains()).isEmpty();
    }

    @Test
    void parallelRuleMustRequireRequireParallelContextFlag() {
        when(planningService.readPlan(any())).thenReturn(null);

        SupervisorGraphState state = node.apply(state(
                Map.of(), "同时分析这套房源的交易风险"));

        assertThat(state.parallelDomains()).isEmpty();
    }

    @Test
    void contextParallelDomainsMustTakePrecedence() {
        when(planningService.readPlan(any())).thenReturn(null);
        when(catalog.contains("listing")).thenReturn(true);
        when(catalog.contains("compare")).thenReturn(true);

        SupervisorGraphState state = node.apply(state(
                Map.of("parallelDomains", List.of("listing", "compare")), "query"));

        assertThat(state.parallelDomains()).containsExactly("listing", "compare");
    }

    private SupervisorGraphState state(Map<String, Object> context, String message) {
        return SupervisorGraphState.initialize("session-1", "task-1", "trace-1", "user-1", message)
                .withSharedContext(context);
    }
}
