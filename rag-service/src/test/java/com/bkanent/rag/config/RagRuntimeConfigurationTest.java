package com.bkanent.rag.config;

import com.bkanent.common.rag.*;
import com.bkanent.rag.index.*;
import com.bkanent.rag.rerank.BgeRerankService;
import com.bkanent.rag.rerank.RerankEndpoint;
import com.bkanent.rag.storage.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.retry.backoff.NoBackOffPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagRuntimeConfigurationTest {
    @Test void basicWiringConstructsWithoutNetworkOrBackendInvocations() {
        var keyword = mock(ElasticsearchProjectionStore.class);
        var vector = mock(SpringAiMilvusDocumentStore.class);
        var rerank = mock(BgeRerankService.class);
        var embedding = mock(EmbeddingModel.class);
        when(keyword.backend()).thenReturn(IndexSink.Backend.KEYWORD);
        when(vector.backend()).thenReturn(IndexSink.Backend.VECTOR);
        DataSource db = new DriverManagerDataSource("jdbc:h2:mem:wiring;DB_CLOSE_DELAY=-1", "sa", "");
        new ApplicationContextRunner().withUserConfiguration(RagRuntimeConfiguration.class)
                .withBean(DataSource.class, () -> db)
                .withBean(ElasticsearchProjectionStore.class, () -> keyword)
                .withBean(SpringAiMilvusDocumentStore.class, () -> vector)
                .withBean(BgeRerankService.class, () -> rerank)
                .withBean("openAiEmbeddingModel", EmbeddingModel.class, () -> embedding)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ManagedRagSpaceRegistry.class).hasSingleBean(RagIndexService.class).hasSingleBean(RagIndexWorker.class);
                    assertThat(context.getBean(ManagedRagSpaceRegistry.class).snapshot()).isEmpty();
                    assertThat(context.getBean("milvusClient")).isNotNull();
                    verify(keyword).backend(); verify(vector).backend();
                    verifyNoMoreInteractions(keyword, vector);
                    verifyNoInteractions(rerank, embedding);
                });
    }
    @Test void actualEmbeddingRetryTemplateIsSingleAttemptWithoutBackoff() {
        var environment = new MockEnvironment()
                .withProperty("spring.ai.openai.api-key", "")
                .withProperty("spring.ai.openai.base-url", "")
                .withProperty("spring.ai.openai.embedding.options.model", "offline-fixture");
        var db = mock(DataSource.class);
        var keyword = mock(ElasticsearchProjectionStore.class);
        var vector = mock(SpringAiMilvusDocumentStore.class);
        var rerank = mock(BgeRerankService.class);
        when(keyword.backend()).thenReturn(IndexSink.Backend.KEYWORD);
        when(vector.backend()).thenReturn(IndexSink.Backend.VECTOR);
        var calls = new AtomicInteger();
        URI endpoint = URI.create("https://embedding.invalid/v1/embeddings");
        ClientHttpRequestFactory offlineRequests = (uri, method) -> {
            assertThat(uri).isEqualTo(endpoint);
            assertThat(method).isEqualTo(HttpMethod.POST);
            return new MockClientHttpRequest(method, uri) {
                @Override protected ClientHttpResponse executeInternal() {
                    calls.incrementAndGet();
                    return new MockClientHttpResponse("fixed".getBytes(StandardCharsets.UTF_8),
                            HttpStatus.SERVICE_UNAVAILABLE);
                }
            };
        };
        // Two real templates make @Primary necessary; the alternative would retry this 503 three times.
        var competingTemplate = RetryTemplate.builder().maxAttempts(3).noBackoff().build();
        new ApplicationContextRunner(() -> {
            var context = new AnnotationConfigApplicationContext();
            context.setEnvironment(environment);
            return context;
        }).withUserConfiguration(RagRuntimeConfiguration.class)
                .withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class,
                        OpenAiEmbeddingAutoConfiguration.class))
                .withBean(DataSource.class, () -> db)
                .withBean(ElasticsearchProjectionStore.class, () -> keyword)
                .withBean(SpringAiMilvusDocumentStore.class, () -> vector)
                .withBean(BgeRerankService.class, () -> rerank)
                .withBean("retryTemplate", RetryTemplate.class, () -> competingTemplate)
                .withBean(RestClient.Builder.class, () -> RestClient.builder().requestFactory(offlineRequests))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RagIndexService.class);
                    assertThat(context.getEnvironment()).isSameAs(environment);
                    assertThat(environment.getPropertySources().contains("systemProperties")).isFalse();
                    assertThat(environment.getPropertySources().contains("systemEnvironment")).isFalse();
                    var factory = context.getBeanFactory();
                    assertThat(factory.getBeanDefinition("openAiEmbeddingModel").getFactoryBeanName())
                            .isEqualTo(OpenAiEmbeddingAutoConfiguration.class.getName());
                    assertThat(factory.getBeanDefinition("openAiEmbeddingModel").isLazyInit()).isTrue();
                    assertThat(factory.containsSingleton("openAiEmbeddingModel")).isFalse();
                    assertThat(factory.containsSingleton("ragActualMilvusClient")).isFalse();
                    assertThat(context.getBean(ManagedRagSpaceRegistry.class).snapshot()).isEmpty();
                    EmbeddingModel model = context.getBean(EmbeddingModel.class);
                    assertThat(model).isSameAs(context.getBean("ragEmbeddingModel"));
                    assertThat(factory.containsSingleton("openAiEmbeddingModel")).isFalse();
                    assertThat(calls.get()).isZero();

                    var template = context.getBean("ragEmbeddingRetryTemplate", RetryTemplate.class);
                    assertThat(context.getBeansOfType(RetryTemplate.class)).hasSize(2);
                    assertThat(context.getBean(RetryTemplate.class)).isSameAs(template).isNotSameAs(competingTemplate);
                    assertThat(ReflectionTestUtils.getField(template, "backOffPolicy"))
                            .isInstanceOf(NoBackOffPolicy.class);
                    // Official 1.1.2 factory requires nonblank connection values. Supply only an in-memory
                    // unusable placeholder after proving credential-free base startup; no property files or sockets.
                    var connection = context.getBean(OpenAiConnectionProperties.class);
                    assertThat(connection.getApiKey()).isEmpty();
                    assertThat(connection.getBaseUrl()).isEmpty();
                    connection.setApiKey("offline-placeholder-not-a-credential");
                    connection.setBaseUrl("https://embedding.invalid");
                    assertThatThrownBy(() -> model.embed("offline retry diagnostic"))
                            .isInstanceOf(TransientAiException.class).hasMessage("HTTP 503 - fixed");
                    assertThat(calls.get()).isEqualTo(1);
                    var actual = context.getBean("openAiEmbeddingModel", OpenAiEmbeddingModel.class);
                    assertThat(ReflectionTestUtils.getField(actual, "retryTemplate")).isSameAs(template);
                    assertThat(factory.containsSingleton("ragActualMilvusClient")).isFalse();
                    verify(keyword).backend();
                    verify(vector).backend();
                    verifyNoMoreInteractions(keyword, vector);
                    verifyNoInteractions(db, rerank);
                });
    }
    @Test void authReferenceDefersDiscoveryAndDisablesRetries() throws Exception {
        DubboReference ref = RagRuntimeConfiguration.class.getDeclaredField("auth").getAnnotation(DubboReference.class);
        assertThat(ref.check()).isFalse(); assertThat(ref.init()).isFalse(); assertThat(ref.retries()).isZero();
    }
    @Test void rerankEndpointBindsGovernedProtocolAndIdentityPins() {
        DataSource db = new DriverManagerDataSource("jdbc:h2:mem:rerank-endpoint;DB_CLOSE_DELAY=-1", "sa", "");
        var keyword = mock(ElasticsearchProjectionStore.class);
        var vector = mock(SpringAiMilvusDocumentStore.class);
        var rerank = mock(BgeRerankService.class);
        var embedding = mock(EmbeddingModel.class);
        when(keyword.backend()).thenReturn(IndexSink.Backend.KEYWORD);
        when(vector.backend()).thenReturn(IndexSink.Backend.VECTOR);
        new ApplicationContextRunner().withUserConfiguration(RagRuntimeConfiguration.class)
                .withBean(DataSource.class, () -> db)
                .withBean(ElasticsearchProjectionStore.class, () -> keyword)
                .withBean(SpringAiMilvusDocumentStore.class, () -> vector)
                .withBean(BgeRerankService.class, () -> rerank)
                .withBean("openAiEmbeddingModel", EmbeddingModel.class, () -> embedding)
                .withPropertyValues("rag.rerank.endpoint=http://bkanent-reranker-dev:18111/api/v1/rerank",
                        "rag.rerank.model=bge-reranker-v2-m3-v1", "rag.rerank.protocol=governed",
                        "rag.rerank.expected-revision=rev-1", "rag.rerank.expected-artifact-digest=digest-1")
                .run(context -> {
                    RerankEndpoint endpoint = context.getBean(RerankEndpoint.class);
                    assertThat(endpoint.protocol()).isEqualTo(RerankEndpoint.Protocol.GOVERNED);
                    assertThat(endpoint.model()).isEqualTo("bge-reranker-v2-m3-v1");
                    assertThat(endpoint.expectedRevision()).isEqualTo("rev-1");
                    assertThat(endpoint.expectedArtifactDigest()).isEqualTo("digest-1");
                });
    }
    @Test void controlReadinessRequiresRealSchemaNotJustDatabasePort() throws Exception {
        DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:readiness;DB_CLOSE_DELAY=-1", "sa", "");
        var security = new RagSecurityProperties(Map.of("caller", new RagSecurityProperties.TrustedKey("agent-service", null,
                Set.of(RagOperation.SEARCH), "C".repeat(32))), "rag-current", "A".repeat(32), "auth-current", "R".repeat(32));
        var health = RagHealthConfiguration.baseIndicator(db, security, () -> true);
        assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE rag_index_event(id BIGINT)"); s.execute("CREATE TABLE rag_document_state(id BIGINT)");
        }
        assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
        assertThat(RagHealthConfiguration.baseIndicator(db, security, () -> false).health().getStatus().getCode()).isEqualTo("DOWN");
        assertThat(RagHealthConfiguration.baseIndicator(db, new RagSecurityProperties(null, null, null, null, null), () -> true)
                .health().getStatus().getCode()).isEqualTo("DOWN");
    }
    @Test void diagnosticsRetainTypedAndSpringAiAuthorizationFailuresWithoutLeakingBodies() {
        for (int status : List.of(401, 403)) {
            RagErrorCode expected = status == 401 ? RagErrorCode.AUTHENTICATION_FAILED : RagErrorCode.FORBIDDEN;
            var http = new org.springframework.web.client.RestClientResponseException("sensitive", status, "denied", null, null, null);
            assertThat(RagRuntimeConfiguration.capabilityFailure(new IllegalStateException("wrapper", http))).isEqualTo(expected);
            assertThat(RagRuntimeConfiguration.capabilityFailure(new org.springframework.ai.retry.NonTransientAiException("HTTP " + status + " - sensitive"))).isEqualTo(expected);
            assertThat(RagRuntimeConfiguration.capabilityFailure(new org.springframework.ai.retry.TransientAiException("HTTP " + status + " - sensitive"))).isEqualTo(expected);
            var response = mock(org.elasticsearch.client.Response.class);
            when(response.getRequestLine()).thenReturn(new org.apache.http.message.BasicRequestLine("GET", "/", org.apache.http.HttpVersion.HTTP_1_1));
            when(response.getHost()).thenReturn(new org.apache.http.HttpHost("diagnostic.invalid"));
            when(response.getStatusLine()).thenReturn(new org.apache.http.message.BasicStatusLine(org.apache.http.HttpVersion.HTTP_1_1, status, "denied"));
            try {
                assertThat(RagRuntimeConfiguration.capabilityFailure(new RuntimeException(new org.elasticsearch.client.ResponseException(response)))).isEqualTo(expected);
            } catch (java.io.IOException unexpected) { throw new AssertionError(unexpected); }
        }
        assertThat(RagRuntimeConfiguration.capabilityFailure(new RuntimeException(io.grpc.Status.UNAUTHENTICATED.asRuntimeException()))).isEqualTo(RagErrorCode.AUTHENTICATION_FAILED);
        assertThat(RagRuntimeConfiguration.capabilityFailure(new RuntimeException(io.grpc.Status.PERMISSION_DENIED.asException()))).isEqualTo(RagErrorCode.FORBIDDEN);
        assertThat(RagRuntimeConfiguration.capabilityFailure(new org.springframework.ai.retry.TransientAiException("HTTP 503 - body mentions HTTP 401 - "))).isEqualTo(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        assertThat(RagRuntimeConfiguration.capabilityFailure(new RuntimeException("HTTP 401 - untyped text"))).isEqualTo(RagErrorCode.DEPENDENCY_UNAVAILABLE);
    }
    @Test void serviceKeysCannotShareSecretsOrRotateIntoDifferentOwner() {
        var sameSecret = new RagSecurityProperties(Map.of(
                "a", new RagSecurityProperties.TrustedKey("agent-service", null, Set.of(RagOperation.SEARCH), "S".repeat(32)),
                "b", new RagSecurityProperties.TrustedKey("memory-service", "memory", Set.of(RagOperation.SEARCH), "S".repeat(32))), null, null, null, null);
        assertThatThrownBy(sameSecret::signingKeys).isInstanceOf(RagException.class);
        var wrongOwner = new RagSecurityProperties(Map.of(
                "a", new RagSecurityProperties.TrustedKey("memory-service", "listing-master", Set.of(RagOperation.SEARCH), "S".repeat(32)),
                "b", new RagSecurityProperties.TrustedKey("memory-service", "memory", Set.of(RagOperation.SEARCH), "T".repeat(32))), null, null, null, null);
        assertThatThrownBy(wrongOwner::signingKeys).isInstanceOf(RagException.class);
    }
}
