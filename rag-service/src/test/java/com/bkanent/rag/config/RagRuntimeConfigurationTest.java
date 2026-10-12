package com.bkanent.rag.config;

import com.bkanent.common.rag.*;
import com.bkanent.rag.index.*;
import com.bkanent.rag.rerank.BgeRerankService;
import com.bkanent.rag.storage.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
    @Test void authReferenceDefersDiscoveryAndDisablesRetries() throws Exception {
        DubboReference ref = RagRuntimeConfiguration.class.getDeclaredField("auth").getAnnotation(DubboReference.class);
        assertThat(ref.check()).isFalse(); assertThat(ref.init()).isFalse(); assertThat(ref.retries()).isZero();
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
