package com.bkanent.platform;

import com.bkanent.business.controller.BusinessController;
import com.bkanent.businessreview.web.ReviewController;
import com.bkanent.knowledge.controller.KnowledgeCaseController;
import com.bkanent.platform.a2a.PlatformAgentRouter;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.spec.AgentCard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlatformOperationsStartupTest {
    @Test
    void combinedApplicationLoadsAllControllersAndOneAgent() throws Exception {
        String authorityKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
        new WebApplicationContextRunner().withUserConfiguration(PlatformOperationsApplication.class)
                .withBean(ChatModel.class, () -> mock(ChatModel.class), definition -> definition.setPrimary(true))
                .withPropertyValues("spring.profiles.active=local", "spring.cloud.nacos.config.enabled=false",
                        "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.discovery.enabled=false",
                        "spring.ai.alibaba.a2a.nacos.registry.enabled=false",
                        "spring.ai.alibaba.a2a.nacos.discovery.enabled=false",
                        "dubbo.application.name=platform-startup-test", "dubbo.registry.address=N/A",
                        "dubbo.consumer.lazy=true", "dubbo.consumer.init=false",
                        "spring.ai.deepseek.api-key=test-placeholder",
                        "business-review.authorization.authority-public-keys.auth-service-current=" + authorityKey,
                        "business-review.sources.mode=fixture", "a2a.memory.key-id=test-key",
                        "a2a.memory.private-key-file=test-key.pem",
                        "knowledge.datasource.primary.url=jdbc:h2:mem:platformstart;MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "knowledge.datasource.primary.username=sa", "knowledge.datasource.primary.password=test",
                        "knowledge.datasource.primary.driver-class-name=org.h2.Driver",
                        "knowledge.datasource.source.url=jdbc:h2:mem:platformsource;MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "knowledge.datasource.source.username=sa", "knowledge.datasource.source.password=test",
                        "knowledge.datasource.source.driver-class-name=org.h2.Driver")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BusinessController.class);
                    assertThat(context).hasSingleBean(ReviewController.class);
                    assertThat(context).hasSingleBean(KnowledgeCaseController.class);
                    assertThat(context).hasSingleBean(AgentExecutor.class);
                    assertThat(context.getBean(AgentExecutor.class)).isInstanceOf(PlatformAgentRouter.class);
                    assertThat(context).hasSingleBean(AgentCard.class);
                    assertThat(context).hasSingleBean(com.alibaba.cloud.ai.a2a.core.server.JsonRpcA2aRequestHandler.class);
                    assertThat(context).hasBean("a2aRouterFunction");
                });
    }
}
