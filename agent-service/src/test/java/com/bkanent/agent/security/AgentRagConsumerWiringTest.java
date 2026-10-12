package com.bkanent.agent.security;

import com.bkanent.agent.config.AgentRagConsumerProperties;
import com.bkanent.agent.milvus.memory.AgentMemoryMilvusService;
import com.bkanent.agent.milvus.listing.ListingMilvusService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import static org.assertj.core.api.Assertions.*;

class AgentRagConsumerWiringTest {
    @Test void consumerBeansStartWithoutRagBackendOrAuthenticationRpc() {
        new ApplicationContextRunner().withUserConfiguration(Consumer.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AgentRagCredentialLeaseStore.class)
                    .hasSingleBean(AgentMemoryMilvusService.class).hasSingleBean(ListingMilvusService.class);
            assertThat(context.getBean(AgentRagConsumerProperties.class).isKnowledgeEnabled()).isFalse();
            assertThatThrownBy(() -> context.getBean(AgentMemoryMilvusService.class).searchKnowledge("1", null, "query", 5))
                    .hasMessage("AUTHENTICATION_REQUIRED");
        });
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentRagConsumerProperties.class)
    @Import({AgentRagCredentialLeaseStore.class, AgentRagRequestAuthentication.class,
            AgentRagCredentialFilter.class, AgentMemoryMilvusService.class, ListingMilvusService.class})
    static class Consumer { }
}
