package com.bkanent.platform.a2a;

import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties;
import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties;
import com.bkanent.business.a2a.TradeOfficialA2aAgent;
import com.bkanent.businessreview.a2a.BusinessReviewOfficialA2aAgent;
import com.bkanent.common.a2a.A2aExecutionProperties;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.knowledge.a2a.KnowledgeOfficialA2aAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformAgentConfigurationTest {
    @Test
    void exposesOneCardAndOneExecutorForTheThreeBranches() {
        var analytics = mock(TradeOfficialA2aAgent.class);
        var review = mock(BusinessReviewOfficialA2aAgent.class);
        var knowledge = mock(KnowledgeOfficialA2aAgent.class);
        when(analytics.tradeA2aAgentExecutor(any(), any())).thenReturn(mock(AgentExecutor.class));
        when(review.businessReviewA2aAgentExecutor(any(), any())).thenReturn(mock(AgentExecutor.class));
        when(knowledge.knowledgeA2aAgentExecutor(any(), any())).thenReturn(mock(AgentExecutor.class));
        var base = new AgentCard("trade-agent", "trade", "http://localhost/a2a", null, "1", null,
                new AgentCapabilities(true, false, true, List.of()), List.of("text"), List.of("text"),
                List.of(), false, Map.of(), List.of(), null, List.of(), "JSONRPC", "0.2.5");
        when(analytics.tradePublishedAgentCard(any(), any())).thenReturn(base);
        var skills = mock(SkillRegistry.class);
        when(skills.findOperationalSkills(any())).thenReturn(List.of());
        new ApplicationContextRunner().withUserConfiguration(PlatformAgentConfiguration.class)
                .withBean(TradeOfficialA2aAgent.class, () -> analytics)
                .withBean(BusinessReviewOfficialA2aAgent.class, () -> review)
                .withBean(KnowledgeOfficialA2aAgent.class, () -> knowledge)
                .withBean(SkillRegistry.class, () -> skills)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(A2aExecutionProperties.class, A2aExecutionProperties::new)
                .withBean(A2aServerProperties.class, A2aServerProperties::new)
                .withBean(A2aServerAgentCardProperties.class, A2aServerAgentCardProperties::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(AgentExecutor.class)).hasSize(1);
                    assertThat(context.getBean(AgentExecutor.class)).isInstanceOf(PlatformAgentRouter.class);
                    assertThat(context.getBeansOfType(AgentCard.class)).hasSize(1);
                    AgentCard card = context.getBean(AgentCard.class);
                    assertThat(card.name()).isEqualTo("platform-operations-agent");
                    assertThat(card.capabilities().extensions().get(0).params().get("skillOwners"))
                            .isEqualTo(List.of("trade", "business-review", "knowledge"));
                });
    }
}
