package com.bkanent.rag.config;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.rag.index.*;
import com.bkanent.rag.rerank.*;
import com.bkanent.rag.security.RagAccessControl;
import com.bkanent.rag.security.http.RagHttpDeadline;
import com.bkanent.rag.storage.*;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RagProperties.class, RagSecurityProperties.class})
public class RagRuntimeConfiguration {
    @DubboReference(check = false, init = false, timeout = 5000, retries = 0)
    private RagPrincipalRpcService auth;

    @Bean Clock ragClock() { return Clock.systemUTC(); }

    @Bean ManagedRagSpaceRegistry ragSpaceRegistry(RagProperties properties, ObjectProvider<RagCapabilityProbe> probes, Clock clock) {
        return new ManagedRagSpaceRegistry(properties, () -> probes.orderedStream().toList(), clock);
    }
    @Bean RagAccessControl ragAccessControl(ManagedRagSpaceRegistry registry, RagSecurityProperties security, Clock clock) {
        return new RagAccessControl(registry, security, () -> auth, clock);
    }
    @Bean JdbcRagIndexRepository ragIndexRepository(DataSource dataSource) { return new JdbcRagIndexRepository(dataSource); }
    @Bean RagIndexService ragIndexService(JdbcRagIndexRepository repository, Clock clock) { return new RagIndexService(repository, clock); }
    @Bean RagIndexWorker ragIndexWorker(JdbcRagIndexRepository repository, List<IndexSink> sinks) { return new RagIndexWorker(repository, sinks); }

    @Bean RerankEndpoint ragRerankEndpoint(@Value("${rag.rerank.endpoint:}") String endpoint,
            @Value("${rag.rerank.api-key:}") String apiKey, @Value("${rag.rerank.model:}") String model,
            @Value("${rag.rerank.protocol:}") String protocol,
            @Value("${rag.rerank.expected-revision:}") String expectedRevision,
            @Value("${rag.rerank.expected-artifact-digest:}") String expectedArtifactDigest) {
        URI uri = null;
        RerankEndpoint.Protocol type = null;
        try { if (!endpoint.isBlank()) uri = URI.create(endpoint); }
        catch (IllegalArgumentException invalid) { /* Invalid deployment target is a capability gate, not a base outage. */ }
        try { if (!protocol.isBlank()) type = RerankEndpoint.Protocol.valueOf(protocol.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { /* No guessed protocol. */ }
        return new RerankEndpoint(uri, apiKey, model, type, expectedRevision, expectedArtifactDigest);
    }

    /** Keep the existing starter model, but do not require its credentials/network during base startup. */
    @Bean static BeanFactoryPostProcessor ragLazyBackendDefinitions() {
        return factory -> {
            if (factory.containsBeanDefinition("openAiEmbeddingModel")) factory.getBeanDefinition("openAiEmbeddingModel").setLazyInit(true);
        };
    }
    @Bean @Primary EmbeddingModel ragEmbeddingModel(@Lazy @Qualifier("openAiEmbeddingModel") EmbeddingModel target) { return target; }

    @Bean(destroyMethod = "") @Primary MilvusServiceClient milvusClient(@Lazy @Qualifier("ragActualMilvusClient") MilvusServiceClient target) { return target; }
    @Bean(destroyMethod = "") @Lazy
    MilvusServiceClient ragActualMilvusClient(@Value("${rag.milvus.host:}") String host,
            @Value("${rag.milvus.port:19530}") int port, @Value("${rag.milvus.token:}") String token,
            @Value("${rag.milvus.secure:false}") boolean secure,
            @Value("${rag.milvus.connect-timeout-ms:5000}") long connectTimeout,
            @Value("${rag.milvus.rpc-deadline-ms:5000}") long rpcDeadline) {
        if (host.isBlank() || host.contains("://") || port < 1 || port > 65535
                || connectTimeout < 1 || connectTimeout > 5000 || rpcDeadline < 1 || rpcDeadline > 5000) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "milvusConfiguration");
        }
        return new MilvusServiceClient(ConnectParam.newBuilder().withHost(host).withPort(port)
                .withAuthorization(token).withSecure(secure).withConnectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .withRpcDeadline(rpcDeadline, TimeUnit.MILLISECONDS).build());
    }

    /** Local diagnostic boundary: retain authorization failures without publishing exception bodies or endpoints. */
    static RagErrorCode capabilityFailure(Throwable failure) {
        RagErrorCode fallback = RagErrorCode.DEPENDENCY_UNAVAILABLE;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof RagException rag) {
                if (rag.code() == RagErrorCode.ENDPOINT_POLICY_DENIED || rag.code() == RagErrorCode.FORBIDDEN
                        || rag.code() == RagErrorCode.AUTHENTICATION_FAILED || rag.code() == RagErrorCode.AUTHENTICATION_EXPIRED) return rag.code();
                fallback = rag.code();
            }
            int status = 0;
            if (cause instanceof org.elasticsearch.client.ResponseException response) status = response.getResponse().getStatusLine().getStatusCode();
            else if (cause instanceof org.springframework.web.client.RestClientResponseException response) status = response.getStatusCode().value();
            else if (cause instanceof org.springframework.ai.retry.NonTransientAiException
                    || cause instanceof org.springframework.ai.retry.TransientAiException) {
                // Spring AI 1.1.2 drops the typed HTTP cause; only its fixed status prefix is interpreted.
                String message = cause.getMessage();
                if (message != null && message.startsWith("HTTP 401 - ")) status = 401;
                else if (message != null && message.startsWith("HTTP 403 - ")) status = 403;
            }
            if (status == 401) return RagErrorCode.AUTHENTICATION_FAILED;
            if (status == 403) return RagErrorCode.FORBIDDEN;
            if (cause instanceof io.grpc.StatusRuntimeException || cause instanceof io.grpc.StatusException) {
                io.grpc.Status.Code code = io.grpc.Status.fromThrowable(cause).getCode();
                if (code == io.grpc.Status.Code.UNAUTHENTICATED) return RagErrorCode.AUTHENTICATION_FAILED;
                if (code == io.grpc.Status.Code.PERMISSION_DENIED) return RagErrorCode.FORBIDDEN;
            }
        }
        return fallback;
    }

    @Bean RagCapabilityProbe keywordCapability(ElasticsearchProjectionStore store) {
        return new RagCapabilityProbe() {
            public RagStage stage() { return RagStage.KEYWORD; }
            public void inspect(RagSpaceDefinition space) {
                try (var ignored = RagHttpDeadline.open(Duration.ofMillis(Math.min(5000, space.limits().recallTimeoutMillis())))) { store.inspectSchema(space); }
            }
            public void provision(RagSpaceDefinition space) {
                try (var ignored = RagHttpDeadline.open(Duration.ofMillis(Math.min(5000, space.limits().recallTimeoutMillis())))) { store.provision(space); }
            }
        };
    }
    @Bean RagCapabilityProbe vectorCapability(SpringAiMilvusDocumentStore store) {
        return new RagCapabilityProbe() {
            public RagStage stage() { return RagStage.VECTOR; }
            public void inspect(RagSpaceDefinition space) { store.verifySchema(space); }
            public void provision(RagSpaceDefinition space) { store.provision(space.spaceId()); }
        };
    }
    @Bean RagCapabilityProbe embeddingCapability(EmbeddingModel model, @Value("${spring.ai.openai.embedding.options.model:}") String modelVersion) {
        return new RagCapabilityProbe() {
            public RagStage stage() { return RagStage.EMBEDDING; }
            public void inspect(RagSpaceDefinition space) {
                if (!space.vectorSchema().modelVersion().equals(modelVersion)) throw new RagException(RagErrorCode.SPACE_NOT_READY, "embeddingModelVersion");
                try (var ignored = RagHttpDeadline.open(Duration.ofMillis(Math.min(5000, space.limits().recallTimeoutMillis())))) {
                    float[] embedding = model.embed("RAG capability diagnostic");
                    if (embedding == null || embedding.length != space.vectorSchema().dimension()) throw new RagException(RagErrorCode.SPACE_NOT_READY, "embeddingDimension");
                    for (float value : embedding) if (!Float.isFinite(value)) throw new RagException(RagErrorCode.SPACE_NOT_READY, "embeddingDimension");
                }
            }
        };
    }
    @Bean RagCapabilityProbe rerankCapability(BgeRerankService service) {
        return new RagCapabilityProbe() {
            public RagStage stage() { return RagStage.RERANK; }
            public void inspect(RagSpaceDefinition space) {
                if (space.rerankProfile() == null) throw new RagException(RagErrorCode.SCORING_PROFILE_MISMATCH);
                space.rerankProfile().normalize(0);
                RagCandidate fixture = new RagCandidate("diagnostic", "diagnostic", "diagnostic", "RAG capability diagnostic",
                        1, space.schemaVersion(), List.of(RagRecallMode.KEYWORD), null, null, null, null, null,
                        RagScoreKind.RANK_SINGLE, "diagnostic", Map.of());
                try (var ignored = RagHttpDeadline.open(Duration.ofMillis(Math.min(8000, space.limits().rerankTimeoutMillis())))) {
                    List<RagCandidate> result = service.rerank("RAG capability diagnostic", List.of(fixture), space.rerankProfile());
                    if (result.size() != 1 || result.get(0).scoreKind() != RagScoreKind.SEMANTIC_NORMALIZED) throw new RagException(RagErrorCode.RERANK_PROTOCOL_ERROR);
                }
            }
        };
    }
}
