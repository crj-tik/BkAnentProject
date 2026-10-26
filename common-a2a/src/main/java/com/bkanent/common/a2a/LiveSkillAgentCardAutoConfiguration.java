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

/**
 * KI-47：各服务用 SkillAgentCardPublisher 自定义 AgentCard bean 时会抑制 Starter 的
 * A2aServerAgentCardAutoConfiguration（类级 ConditionalOnMissingBean(AgentCard)），其
 * EnableConfigurationProperties 注册的 A2aServerProperties / A2aServerAgentCardProperties
 * 随之消失，而服务自己的 Card bean 又以它们为参数。此处统一注册，注册器幂等，
 * Starter 自动配置正常执行时不会重复注册。
 */
@AutoConfiguration(after = {com.bkanent.common.skill.config.SkillAutoConfiguration.class,
        org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class},
        before = com.alibaba.cloud.ai.a2a.autoconfigure.server.A2aServerHandlerAutoConfiguration.class)
@org.springframework.boot.context.properties.EnableConfigurationProperties({A2aExecutionProperties.class,
        A2aAuthorizationProperties.class,
        com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerProperties.class,
        com.alibaba.cloud.ai.a2a.autoconfigure.A2aServerAgentCardProperties.class})
public class LiveSkillAgentCardAutoConfiguration {
    @Bean
    @ConditionalOnBean(SkillRegistry.class)
    LiveSkillAgentCards liveSkillAgentCards(SkillRegistry registry, ObjectProvider<AgentCard> cards,
                                           ObjectProvider<NacosA2aOperationService> nacos) {
        return new LiveSkillAgentCards(registry, cards, nacos);
    }

    @Bean
    A2aBeaconAuthentication a2aBeaconAuthentication(org.springframework.core.env.Environment environment) {
        return new A2aBeaconAuthentication(environment);
    }

    @Bean
    @ConditionalOnBean(javax.sql.DataSource.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(A2aTaskAuthorizationStore.class)
    A2aTaskAuthorizationStore a2aTaskAuthorizationStore(javax.sql.DataSource dataSource) {
        return new JdbcA2aTaskAuthorizationStore(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    @Bean
    A2aAuthorizationVerifier a2aAuthorizationVerifier(A2aBeaconAuthentication authentication,
                                                       A2aAuthorizationProperties properties,
                                                       ObjectProvider<A2aTaskAuthorizationStore> stateStores) {
        // The disabled compatibility path has no audience to verify. Avoid requiring application
        // identity until the signed protocol is explicitly enabled.
        String audience = properties.isEnabled() ? authentication.service() : "disabled-service";
        A2aTaskAuthorizationStore stateStore = stateStores.getIfAvailable();
        if (properties.isEnabled() && stateStore == null) {
            throw new IllegalStateException("Enabled A2A authorization requires a durable task authorization store");
        }
        if (properties.isEnabled() && stateStore instanceof JdbcA2aTaskAuthorizationStore jdbcStore) {
            jdbcStore.verifySchema();
        }
        return new A2aAuthorizationVerifier(audience, properties, java.time.Clock.systemUTC(), stateStore);
    }
    @Bean
    A2aAuthenticatedRequestGuard a2aAuthenticatedRequestGuard(A2aBeaconAuthentication authentication,
                                                               A2aAuthorizationVerifier verifier) {
        return new A2aAuthenticatedRequestGuard(authentication, verifier);
    }
    @Bean
    A2aContextExecutorProvider a2aContextExecutorProvider() { return new A2aContextExecutorProvider(); }

    @Bean
    static BeanPostProcessor liveSkillCardHttpHandler(ObjectProvider<LiveSkillAgentCards> cards,
            ObjectProvider<A2aAuthenticatedRequestGuard> guard) {
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
                        try { return guard.getObject().handle(body, headers, () -> delegate.onHandler(body, headers)); }
                        catch (com.bkanent.common.rag.RagException failure) {
                            Object id = null;
                            try {
                                var node = io.a2a.util.Utils.OBJECT_MAPPER.readTree(body).path("id");
                                if (node.isTextual()) id = node.textValue();
                                else if (node.isIntegralNumber() && node.canConvertToInt()) id = node.intValue();
                            } catch (Exception ignored) { }
                            return new io.a2a.spec.JSONRPCErrorResponse(id, new io.a2a.spec.JSONRPCError(-32001,
                                    "Authentication or task access denied", java.util.Map.of("errorCode", failure.code().name())));
                        }
                    }
                };
            }
        };
    }
}
