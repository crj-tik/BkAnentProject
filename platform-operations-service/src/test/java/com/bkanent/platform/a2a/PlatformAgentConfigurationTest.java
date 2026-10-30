package com.bkanent.platform.a2a;

import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties;
import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties;
import com.bkanent.business.a2a.PlatformAnalyticsAgent;
import com.bkanent.businessreview.a2a.PlatformReviewAgent;
import com.bkanent.common.a2a.A2aExecutionProperties;
import com.bkanent.common.skill.SkillDefinition;
import com.bkanent.common.skill.core.SkillFileLoader;
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
    void everyPlatformOperationsBranchHasAnOperationalSkillForCardPublication() {
        var registry = new SkillRegistry(new SkillFileLoader(), null);

        assertThat(registry.findOperationalSkills("analytics")).isNotEmpty();
        assertThat(registry.findOperationalSkills("review")).isNotEmpty();
        assertThat(registry.findOperationalSkills("knowledge")).extracting(SkillDefinition::name)
                .contains("platform-operations-knowledge-operations");
        assertThat(registry.allSkills()).extracting(SkillDefinition::domain)
                .containsExactlyInAnyOrder("analytics", "review", "knowledge");
    }

    @Test
    void exposesOneCardAndOneExecutorForTheThreeBranches() {
        var analytics = mock(PlatformAnalyticsAgent.class);
        var review = mock(PlatformReviewAgent.class);
        var knowledge = mock(KnowledgeOfficialA2aAgent.class);
        when(analytics.analyticsReactAgent()).thenReturn(mock(com.alibaba.cloud.ai.graph.agent.ReactAgent.class));
        when(analytics.analyticsA2aAgentExecutor(any(), any())).thenReturn(mock(AgentExecutor.class));
        when(review.reviewA2aAgentExecutor(any(), any())).thenReturn(mock(AgentExecutor.class));
        when(knowledge.knowledgeA2aAgentExecutor(any(), any())).thenReturn(mock(AgentExecutor.class));
        var base = new AgentCard("platform-operations-analytics", "analytics", "http://localhost/a2a", null, "1", null,
                new AgentCapabilities(true, false, true, List.of()), List.of("text"), List.of("text"),
                List.of(), false, Map.of(), List.of(), null, List.of(), "JSONRPC", "0.2.5");
        when(analytics.analyticsPublishedAgentCard(any(), any())).thenReturn(base);
        var skills = mock(SkillRegistry.class);
        when(skills.findOperationalSkills("analytics")).thenReturn(List.of(skill("analytics",
                "platform-operations-kpi-report")));
        when(skills.findOperationalSkills("review")).thenReturn(List.of(skill("review",
                "platform-operations-store-review-draft")));
        when(skills.findOperationalSkills("knowledge")).thenReturn(List.of(skill("knowledge",
                "platform-operations-knowledge-operations")));
        new ApplicationContextRunner().withUserConfiguration(PlatformAgentConfiguration.class)
                .withBean(PlatformAnalyticsAgent.class, () -> analytics)
                .withBean(PlatformReviewAgent.class, () -> review)
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
                            .isEqualTo(List.of("analytics", "review", "knowledge"));
                    assertThat(card.skills()).extracting("name").containsExactly(
                            "platform-operations-kpi-report",
                            "platform-operations-store-review-draft",
                            "platform-operations-knowledge-operations");
                    assertThat(card.capabilities().extensions().get(0).params().get("skills"))
                            .asList().hasSize(3);
                });
    }

    private static SkillDefinition skill(String owner, String name) {
        return SkillDefinition.builder().name(name).description("capability")
                .domain(owner).owner(owner).version("1.0.0").explicitOnly(true).build();
    }
}
