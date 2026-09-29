package com.bkanent.agent.graph.node;

import com.bkanent.agent.catalog.DomainCatalog;
import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.service.SupervisorIntentPlanningService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParseIntentNodeTest {

    private final SupervisorIntentPlanningService planningService = mock(SupervisorIntentPlanningService.class);
    private final DomainCatalog catalog = mock(DomainCatalog.class);
    private final DistributedAgentProperties properties = new DistributedAgentProperties();
    private final ParseIntentNode node = new ParseIntentNode(planningService, catalog, properties);

    @Test
    void keywordHitMustResolveConfiguredDomain() {
        when(planningService.readPlan(any())).thenReturn(null);
        when(catalog.contains("contract")).thenReturn(true);
        when(catalog.resolveDefaultIntent("contract")).thenReturn("contract.risk_review");

        SupervisorGraphState state = node.apply(state("帮我归档这份合同"));

        assertThat(state.domain()).isEqualTo("contract");
        assertThat(state.intent()).isEqualTo("contract.risk_review");
    }

    @Test
    void keywordForNonCatalogDomainMustBeIgnoredAndFallBackToDefault() {
        when(planningService.readPlan(any())).thenReturn(null);
        when(catalog.contains("marketing")).thenReturn(false);
        when(catalog.contains("listing")).thenReturn(true);
        when(catalog.resolveDefaultIntent("listing")).thenReturn("listing.search");

        SupervisorGraphState state = node.apply(state("写一段营销文案"));

        assertThat(state.domain()).isEqualTo("listing");
    }

    @Test
    void configuredNewDomainKeywordMustTakeEffectWithoutCodeChange() {
        properties.getCatalog().getRuleRouting().getKeywords().put("compare", List.of("对比"));
        when(planningService.readPlan(any())).thenReturn(null);
        when(catalog.contains("compare")).thenReturn(true);
        when(catalog.resolveDefaultIntent("compare")).thenReturn("compare.listings");

        SupervisorGraphState state = node.apply(state("对比这几套房源"));

        assertThat(state.domain()).isEqualTo("compare");
        assertThat(state.intent()).isEqualTo("compare.listings");
    }

    @Test
    void noKeywordHitMustFallBackToConfiguredDefaultDomain() {
        when(planningService.readPlan(any())).thenReturn(null);
        when(catalog.contains("listing")).thenReturn(true);
        when(catalog.resolveDefaultIntent("listing")).thenReturn("listing.search");

        SupervisorGraphState state = node.apply(state("你好"));

        assertThat(state.domain()).isEqualTo("listing");
    }

    private SupervisorGraphState state(String message) {
        return SupervisorGraphState.initialize("session-1", "task-1", "trace-1", "user-1", message);
    }
}
