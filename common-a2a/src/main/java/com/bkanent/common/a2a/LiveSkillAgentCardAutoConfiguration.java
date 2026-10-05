package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.a2a.core.server.JsonRpcA2aRequestHandler;
import com.alibaba.cloud.ai.a2a.registry.nacos.service.NacosA2aOperationService;
import com.bkanent.common.skill.core.SkillRegistry;
import io.a2a.spec.AgentCard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = com.bkanent.common.skill.config.SkillAutoConfiguration.class)
@org.springframework.boot.context.properties.EnableConfigurationProperties(A2aExecutionProperties.class)
public class LiveSkillAgentCardAutoConfiguration {
    @Bean
    @ConditionalOnBean(SkillRegistry.class)
    LiveSkillAgentCards liveSkillAgentCards(SkillRegistry registry, ObjectProvider<AgentCard> cards,
                                           ObjectProvider<NacosA2aOperationService> nacos) {
        return new LiveSkillAgentCards(registry, cards, nacos);
    }

    @Bean
    static BeanPostProcessor liveSkillCardHttpHandler(ObjectProvider<LiveSkillAgentCards> cards) {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean.getClass() != JsonRpcA2aRequestHandler.class) return bean;
                JsonRpcA2aRequestHandler delegate = (JsonRpcA2aRequestHandler) bean;
                return new JsonRpcA2aRequestHandler(null) {
                    @Override public AgentCard getAgentCard() {
                        LiveSkillAgentCards live = cards.getIfAvailable();
                        return live == null ? delegate.getAgentCard() : live.currentCard(delegate.getAgentCard());
                    }
                    @Override public Object onHandler(String body, org.springframework.web.servlet.function.ServerRequest.Headers headers) {
                        return delegate.onHandler(body, headers);
                    }
                };
            }
        };
    }
}
