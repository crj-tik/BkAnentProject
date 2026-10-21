package com.bkanent.rag.security;

import com.bkanent.common.rag.*;
import com.bkanent.rag.config.ManagedRagSpaceRegistry;
import com.bkanent.rag.config.RagProperties;
import com.bkanent.rag.config.RagSecurityProperties;
import com.bkanent.rag.rerank.RerankProfile;
import com.bkanent.rag.retrieval.RagRetrievalService;
import com.bkanent.rag.rpc.MemoryRagRetrievalRpcServiceImpl;
import com.bkanent.rag.storage.RagSpaceDefinition;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryRagAccessControlTest {
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String KEY_ID = "memory-current";
    private static final String SECRET = "memory-rag-access-control-test-secret-32";

    @Test
    void memorySearchAddsOwnerDomainAgentAndActiveFiltersFromSignedScope() {
        RagSpaceDefinition space = memorySpace();
        RagSearchRequest request = RagSearchRequest.defaults("memory-search", space.spaceId(), "goal");
        long issued = NOW.getEpochSecond();
        var unsignedScope = new MemoryRagAuthorizationScope(MemoryRagAuthorizationScope.VERSION, KEY_ID,
                "memory-service", "42", "marketing", "marketing-agent", "conversation-1", null,
                MemoryRagRecordType.EXPERIENCE, request.spaceId(), request.requestId(),
                RagCanonicalHash.requestHash(request), List.of(), null, issued, issued + 30, null);
        var scope = MemoryRagScopeSigner.sign(unsignedScope, SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var unsignedAuthentication = new RagAuthEnvelope(RagContract.VERSION, KEY_ID, "rag-service", "memory-service",
                RagOperation.MEMORY_SEARCH, request.requestId(), RagCanonicalHash.requestHash(request),
                RagActorMode.SERVICE, "memory-service", issued, issued + 30, null, null);
        var authentication = RagRequestSigner.sign(unsignedAuthentication,
                SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        List<RagFilter> filters = access(Map.of(space.spaceId(), space)).searchMemory(request, authentication, scope);

        assertThat(filters).containsExactly(
                new RagFilter("ownerId", RagFilterOperator.EQ, RagValue.string("42")),
                new RagFilter("active", RagFilterOperator.EQ, RagValue.bool(true)),
                new RagFilter("domainId", RagFilterOperator.EQ, RagValue.string("marketing")),
                new RagFilter("agentId", RagFilterOperator.EQ, RagValue.string("marketing-agent")));
    }

    @Test
    void unregisteredMemorySpaceFailsBeforeEitherRecallBackendIsCalled() {
        RagProperties empty = new RagProperties(Map.of());
        var registry = new ManagedRagSpaceRegistry(empty, List::of, CLOCK);
        RagAccessControl access = access(registry);
        AtomicInteger backendCalls = new AtomicInteger();
        RagRetrievalService retrieval = new RagRetrievalService(registry,
                (space, query, filters, limit, remaining) -> { backendCalls.incrementAndGet(); return List.of(); },
                (space, query, filters, limit, remaining) -> { backendCalls.incrementAndGet(); return List.of(); },
                (query, candidates, profile) -> candidates, (space, candidate) -> true, 2);
        try {
            var rpc = new MemoryRagRetrievalRpcServiceImpl(access, retrieval);
            RagSearchRequest request = RagSearchRequest.defaults("unregistered", "memory.v2.experience.marketing.marketing-agent", "query");
            assertThatThrownBy(() -> rpc.search(request, null, null))
                    .isInstanceOfSatisfying(RagException.class,
                            error -> assertThat(error.code()).isEqualTo(RagErrorCode.NOT_FOUND));
            assertThat(backendCalls).hasValue(0);
        } finally {
            retrieval.close();
        }
    }

    @Test
    void legacyUserSearchCannotEnterMemoryV2Spaces() {
        RagSpaceDefinition space = memorySpace();
        RagAccessControl access = access(Map.of(space.spaceId(), space));
        RagSearchRequest request = RagSearchRequest.defaults("legacy", space.spaceId(), "query");

        assertThatThrownBy(() -> access.search(request, null, null, null))
                .isInstanceOfSatisfying(RagException.class,
                        error -> assertThat(error.code()).isEqualTo(RagErrorCode.FORBIDDEN));
    }

    private static RagAccessControl access(Map<String, RagSpaceDefinition> spaces) {
        return access(new ManagedRagSpaceRegistry(new RagProperties(spaces), List::of, CLOCK));
    }

    private static RagAccessControl access(ManagedRagSpaceRegistry registry) {
        var key = new RagSecurityProperties.TrustedKey("memory-service", "memory-service",
                Set.of(RagOperation.MEMORY_SEARCH), SECRET);
        var security = new RagSecurityProperties(Map.of(KEY_ID, key), "rag-current", "R".repeat(32),
                "auth-response", "A".repeat(32));
        return new RagAccessControl(registry, security, () -> null, CLOCK);
    }

    private static RagSpaceDefinition memorySpace() {
        String id = MemoryRagSpaceIds.spaceId(MemoryRagRecordType.EXPERIENCE, "marketing", "marketing-agent");
        Map<String, RagSpaceDefinition.FilterField> fields = Map.of(
                "ownerId", required(RagValueType.STRING), "domainId", required(RagValueType.STRING),
                "agentId", required(RagValueType.STRING), "active", required(RagValueType.BOOLEAN),
                "experienceId", required(RagValueType.STRING), "sourceVersion", required(RagValueType.NUMBER),
                "sourceHash", required(RagValueType.STRING));
        String physical = "rag_memory_v2_experience_marketing_agent_test";
        return new RagSpaceDefinition(id, "memory-service", "memory-v2", "memory-v2.1", physical,
                new RagSpaceDefinition.VectorSchema("default", physical, "embedding-v1", 3, "doc_id", "content",
                        "metadata", "embedding", "source_type", "source_id", "COSINE", "IVF_FLAT", "{}"),
                fields, Set.of("ownerId", "domainId", "agentId", "active", "experienceId", "sourceVersion", "sourceHash"),
                Set.of("ADMIN", "BROKER"), Set.of("ADMIN"), Map.of("memory-service", Set.of(RagOperation.MEMORY_SEARCH)),
                new RagSpaceDefinition.Limits(500, 500, 200, 8192, 100000, 100000, 20000, 5000, 8000),
                new RerankProfile("native", "reranker", "scoring-v1", RerankProfile.Scale.BOUNDED_0_1),
                "experience", "marketing", "marketing-agent", true);
    }

    private static RagSpaceDefinition.FilterField required(RagValueType type) {
        return new RagSpaceDefinition.FilterField(type, Set.of(RagFilterOperator.EQ), true);
    }
}
