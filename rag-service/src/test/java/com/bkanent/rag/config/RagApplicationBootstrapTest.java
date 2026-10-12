package com.bkanent.rag.config;

import com.bkanent.rag.RagApplication;
import com.bkanent.rag.index.RagIndexService;
import com.bkanent.rag.storage.SpringAiMilvusDocumentStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real production beans, no mocked embedding/Milvus/ES/security/configuration; no backend calls. */
@SpringBootTest(classes = RagApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.config.import=", "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false", "dubbo.registry.address=N/A", "dubbo.provider.export=false",
        "dubbo.protocol.port=-1", "dubbo.application.qos-enable=false", "dubbo.application.register-mode=interface", "dubbo.application.register-consumer=false",
        "spring.datasource.url=jdbc:h2:mem:bootstrap;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.ai.openai.api-key=", "spring.ai.openai.base-url=", "spring.ai.openai.embedding.options.model=",
        "rag.security.auth-request-secret=", "rag.security.auth-response-secret=", "rag.security.auth-response-key-id=",
        "rag.milvus.host=", "rag.rerank.endpoint=", "rag.index.worker.enabled=false"})
@AutoConfigureMockMvc
class RagApplicationBootstrapTest {
    @Autowired ConfigurableApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired ManagedRagSpaceRegistry registry;
    @Test void startsWithMissingBackendCredentialsAndReadinessFailsClosedWithoutNetwork() throws Exception {
        assertThat(context.getBean(RagIndexService.class)).isNotNull();
        assertThat(context.getBeansOfType(org.apache.dubbo.config.spring.ServiceBean.class)).hasSize(3);
        assertThat(context.getBean(SpringAiMilvusDocumentStore.class)).isNotNull();
        assertThat(registry.snapshot()).isEmpty();
        assertThat(context.getBeanFactory().containsSingleton("ragActualMilvusClient")).isFalse();
        assertThat(context.getBeanFactory().containsSingleton("openAiEmbeddingModel")).isFalse();
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
        assertThat(context.getBeanFactory().containsSingleton("ragActualMilvusClient")).isFalse();
        assertThat(context.getBeanFactory().containsSingleton("openAiEmbeddingModel")).isFalse();
    }
}
