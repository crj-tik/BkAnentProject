package com.bkanent.platform.a2a;

import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties;
import com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties;
import com.bkanent.business.a2a.PlatformAnalyticsAgent;
import com.bkanent.businessreview.a2a.PlatformReviewAgent;
import com.bkanent.common.a2a.A2aExecutionProperties;
import com.bkanent.common.a2a.SkillAgentCardPublisher;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.knowledge.a2a.KnowledgeOfficialA2aAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.spec.AgentCard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 一个公开入口，内部执行器和技能域保持独立。 */
@Configuration(proxyBeanMethods = false)
public class PlatformAgentConfiguration {
    /** 框架需要一个 Agent Bean 才会装配 HTTP 处理器；请求执行仍由下方路由器分支处理。 */
    @Bean
    public com.alibaba.cloud.ai.graph.agent.ReactAgent platformEntryAgent(PlatformAnalyticsAgent analytics) {
        return analytics.analyticsReactAgent();
    }

    @Bean
    public AgentExecutor platformAgentExecutor(PlatformAnalyticsAgent analytics,
            PlatformReviewAgent review, KnowledgeOfficialA2aAgent knowledge,
            ObjectMapper mapper, A2aExecutionProperties execution) {
        return new PlatformAgentRouter(analytics.analyticsA2aAgentExecutor(mapper, execution),
                review.reviewA2aAgentExecutor(mapper, execution),
                knowledge.knowledgeA2aAgentExecutor(mapper, execution));
    }

    @Bean
    public AgentCard platformAgentCard(PlatformAnalyticsAgent analytics, SkillRegistry skills,
            A2aServerProperties server, A2aServerAgentCardProperties properties) {
        AgentCard base = analytics.analyticsPublishedAgentCard(server, properties);
        AgentCard platform = new AgentCard("platform-operations-agent", "平台经营分析、门店复盘与知识运营",
                base.url(), base.provider(), base.version(), base.documentationUrl(), base.capabilities(),
                base.defaultInputModes(), base.defaultOutputModes(), base.skills(),
                base.supportsAuthenticatedExtendedCard(), base.securitySchemes(), base.security(),
                base.iconUrl(), base.additionalInterfaces(), base.preferredTransport(), base.protocolVersion());
        return SkillAgentCardPublisher.publish(platform, skills, "platform-operations",
                List.of("analytics", "review", "knowledge"));
    }
}
