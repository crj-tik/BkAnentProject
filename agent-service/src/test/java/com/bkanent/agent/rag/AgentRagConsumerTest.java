package com.bkanent.agent.rag;

import com.bkanent.agent.config.*;
import com.bkanent.agent.milvus.memory.AgentMemoryMilvusService;
import com.bkanent.agent.milvus.listing.ListingMilvusService;
import com.bkanent.agent.milvus.core.model.*;
import com.bkanent.agent.model.rag.*;
import com.bkanent.agent.security.*;
import com.bkanent.agent.tool.AgentMilvusTool;
import com.bkanent.agent.tool.context.*;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRagConsumerTest {
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final AuthPermissionRpcService auth = mock(AuthPermissionRpcService.class);
    private final RagRetrievalRpcService retrieval = mock(RagRetrievalRpcService.class);
    private final MemoryRagRpcService memory = mock(MemoryRagRpcService.class);
    private final RagSpaceAdminRpcService admin = mock(RagSpaceAdminRpcService.class);
    private final RagListingDomainRpcService listing = mock(RagListingDomainRpcService.class);
    private final AgentRagConsumerProperties properties = new AgentRagConsumerProperties();
    private AgentRagCredentialLeaseStore credentials;
    private AgentRagRequestAuthentication signing;
    private AgentMemoryMilvusService wrapper;
    @BeforeEach void setup() {
        beans.registerSingleton("auth", auth); beans.registerSingleton("retrieval", retrieval);
        beans.registerSingleton("memory", memory); beans.registerSingleton("admin", admin); beans.registerSingleton("listing", listing);
        credentials = new AgentRagCredentialLeaseStore(properties, beans.getBeanProvider(AuthPermissionRpcService.class));
        properties.setKeyId("test-key"); properties.setSigningSecret("test-only-secret-at-least-thirty-two-bytes");
        signing = new AgentRagRequestAuthentication(properties);
        wrapper = new AgentMemoryMilvusService(beans.getBeanProvider(RagRetrievalRpcService.class), beans.getBeanProvider(MemoryRagRpcService.class), beans.getBeanProvider(RagSpaceAdminRpcService.class), credentials, signing);
        when(auth.resolvePrincipal("transport-only")).thenReturn(new AuthenticatedPrincipal(1L));
    }
    @AfterEach void cleanup() { AgentToolContextHolder.clear(); }
    @Test void absentHeaderAndUnknownAliasDoNotInvokeRpc() {
        assertThatThrownBy(() -> wrapper.searchKnowledge("1", null, "query", 5)).hasMessage("AUTHENTICATION_REQUIRED");
        assertThatThrownBy(() -> wrapper.searchKnowledge("1", "physical-injected", "query", 5)).hasMessageContaining("INVALID_ARGUMENT");
        verifyNoInteractions(retrieval, memory, admin);
    }
    @Test void supervisorKnowledgeRequiresExplicitAgentOwnerPolicyAndNeverSignsOtherDomain() throws Exception {
        try (var ignored = credentials.openAuthorization("Bearer transport-only")) {
            assertThatThrownBy(() -> wrapper.searchKnowledge("1", null, "query", 5)).hasMessage("FORBIDDEN");
            properties.setKnowledgeEnabled(true); properties.setSupervisorOwnerConfirmed(true);
            properties.setSupervisorOwnerDomain("unknown"); properties.setSupervisorPolicyVersion("v1"); properties.setSupervisorReadUserIds(Set.of("1"));
            assertThatThrownBy(() -> wrapper.searchKnowledge("1", null, "query", 5)).hasMessage("FORBIDDEN");
            properties.setSupervisorOwnerDomain("agent-service");
            assertThatThrownBy(() -> signing.supervisorScope(AgentRagCompatibility.knowledge("listing.knowledge", "query", 5), "1")).hasMessage("FORBIDDEN");
            when(retrieval.search(any(), any(), any(), any())).thenAnswer(call -> {
                RagSearchRequest request = call.getArgument(0); RagAuthEnvelope envelope = call.getArgument(1);
                RagCredentialContext token = call.getArgument(2); RagAuthorizationScope scope = call.getArgument(3);
                assertThat(request.spaceId()).isEqualTo("supervisor.knowledge"); assertThat(request.candidateLimit()).isEqualTo(5);
                assertThat(envelope.actorId()).isEqualTo("1"); assertThat(token.accessToken()).isEqualTo("transport-only");
                assertThat(scope.ownerDomain()).isEqualTo("agent-service");
                return response(request.spaceId(), List.of(candidate("authorized")));
            });
            assertThat(wrapper.searchKnowledge("1", "agent_knowledge", "query", 5)).singleElement().satisfies(result -> {
                assertThat(result.collection()).isEqualTo("agent_knowledge"); assertThat(result.metadata()).containsKeys("degraded", "scoreKind", "projectionVersion");
            });
        }
    }
    @Test void memoryOnlyUsesDomainFinalMatchesNotRawCandidates() throws Exception {
        var allowed = candidate("allowed");
        when(memory.search(any(), any(), any())).thenReturn(new MemoryRagSearchResponse(response("memory.preference", List.of(candidate("not-final-checked"), allowed)), List.of(new MemoryRagMatch(allowed, null, null, .8)), "v1"));
        try (var ignored = credentials.openAuthorization("Bearer transport-only")) {
            assertThat(wrapper.searchKnowledge("1", "user_preference_knowledge", "query", 5)).extracting(MilvusSearchResult::documentId).containsExactly("allowed");
        }
        verifyNoInteractions(retrieval);
    }
    @Test void managedInitChecksDimensionAndNeverProvisionsMismatch() throws Exception {
        when(admin.inspect(any(), any(), any())).thenReturn(new RagSpaceInspection(1, "id", "supervisor.knowledge", "agent-service", "v1", "schema", "embed", 1024, false, List.of()));
        when(admin.provision(any(), any(), any())).thenReturn(new RagSpaceInspection(1, "id", "supervisor.knowledge", "agent-service", "v1", "schema", "embed", 1024, true, List.of()));
        try (var ignored = credentials.openAuthorization("Bearer transport-only")) {
            assertThatThrownBy(() -> wrapper.initializeMemoryCollection("1", new MilvusCollectionInitRequest("agent_knowledge", 1536))).hasMessageContaining("INVALID_ARGUMENT");
            verify(admin, never()).provision(any(), any(), any());
            wrapper.initializeMemoryCollection("1", new MilvusCollectionInitRequest("agent_knowledge", 1024));
            verify(admin).provision(argThat(request -> request.dimension() == 1024 && request.spaceId().equals("supervisor.knowledge")), any(), any());
        }
    }
    @Test void listingDefaultsAndExplicitQuantitiesPreserveOldContractAndLegacyVoidsNeverSend() throws Exception {
        var wrapper = new ListingMilvusService(beans.getBeanProvider(RagListingDomainRpcService.class), properties, credentials, signing);
        when(listing.search(any(), any(), any())).thenAnswer(call -> {
            RagListingSearchRequest request = call.getArgument(0);
            assertThat(request.keywordTopK()).isEqualTo(20); assertThat(request.vectorTopK()).isEqualTo(20); assertThat(request.topK()).isEqualTo(10);
            return new RagListingSearchResponse(1, request.requestId(), List.of(), "old assembled context", response("listing.knowledge", List.of()));
        });
        try (var ignored = credentials.openAuthorization("Bearer transport-only")) {
            assertThat(wrapper.query(query(null, null, null)).assembledContext()).isEqualTo("old assembled context");
            assertThatThrownBy(() -> wrapper.query(query(200, 20, 20))).hasMessageContaining("INVALID_ARGUMENT");
            doAnswer(call -> {
                RagListingSearchRequest request = call.getArgument(0);
                assertThat(request.keywordTopK()).isEqualTo(500);
                assertThat(request.vectorTopK()).isEqualTo(500);
                assertThat(request.topK()).isEqualTo(200);
                return new RagListingSearchResponse(1, request.requestId(), List.of(), "explicit", response("listing.knowledge", List.of()));
            }).when(listing).search(any(), any(), any());
            assertThat(wrapper.query(query(200, 500, 500)).assembledContext()).isEqualTo("explicit");
            assertThatThrownBy(() -> wrapper.query(query(10, 500, 501))).hasMessageContaining("INVALID_ARGUMENT");
        }
        var legacyRpc = new com.bkanent.agent.rpc.AgentRpcServiceImpl(null, wrapper);
        assertThatThrownBy(() -> legacyRpc.syncListingKnowledge(1L)).hasMessage("LEGACY_RECONCILIATION_REQUIRED");
        assertThatThrownBy(() -> legacyRpc.deleteListingKnowledge(1L)).hasMessage("LEGACY_RECONCILIATION_REQUIRED");
        verify(listing, never()).index(any(), any(), any()); verify(listing, never()).reconcile(any(), any());
    }
    @Test void toolIsQueryOnlyDisabledByDefaultAndAuditsSafeErrors() throws Exception {
        var tool = new AgentMilvusTool(wrapper, new AgentChatProperties());
        assertThat(AgentMilvusTool.class.getMethod("searchKnowledge", String.class).getParameterCount()).isEqualTo(1);
        assertThat(tool.searchKnowledge("query")).contains("KNOWLEDGE_SEARCH_DISABLED");
        AgentToolContextHolder.init("1", "supervisor.knowledge", 5, true);
        assertThat(tool.searchKnowledge("query")).contains("AUTHENTICATION_REQUIRED").doesNotContain("PERMISSION_DENIED", "OUTCOME_UNKNOWN");
        assertThat(AgentToolContextHolder.snapshot().firstToolName()).isEqualTo("milvusKnowledgeSearch");
        AgentToolContextHolder.init("1", null, 201, true);
        assertThat(tool.searchKnowledge("query")).contains("INVALID_ARGUMENT");
        verifyNoInteractions(retrieval);
    }
    @Test void historicalMilvusSnapshotAndListingJsonStayReadableWithoutRetrievalExecution() throws Exception {
        var mapper = new ObjectMapper();
        String old = "{\"usedTool\":true,\"firstToolName\":\"milvusKnowledgeSearch\",\"firstToolQuery\":\"q\",\"topK\":5,\"milvusResults\":[{\"collection\":\"agent_knowledge\",\"documentId\":\"old\",\"sourceType\":\"knowledge\",\"sourceId\":\"1\",\"content\":\"old content\",\"score\":0.8,\"metadata\":{}}],\"toolContext\":\"historic\"}";
        var snapshot = mapper.readValue(old, AgentToolSessionSnapshot.class);
        assertThat(snapshot.milvusResults().get(0).content()).isEqualTo("old content");
        assertThat(mapper.readValue("{\"query\":\"q\",\"assembledContext\":\"old\",\"matches\":[]}", ListingRagResponse.class).assembledContext()).isEqualTo("old");
        verifyNoInteractions(retrieval, memory, listing);
    }
    @Test void topKBoundariesProduceLegalCandidatePoolsAndExplicitListingCountsAreNotClamped() {
        for (int count : List.of(1, 5, 100, 200)) {
            var request = AgentRagCompatibility.knowledge("supervisor.knowledge", "query", count);
            assertThat(request.candidateLimit()).isEqualTo(count); assertThat(request.candidateLimit()).isGreaterThanOrEqualTo(count);
        }
        AgentRagCompatibility.listingCounts(250, 250, 200);
        AgentRagCompatibility.listingCounts(500, 500, 200);
        assertThatThrownBy(() -> AgentRagCompatibility.listingCounts(501, 500, 200)).hasMessageContaining("INVALID_ARGUMENT");
    }
    private ListingRagQueryRequest query(Integer top, Integer keyword, Integer vector) { return new ListingRagQueryRequest("1", "query", top, keyword, vector, null, null, null, null, null, null, null); }
    private RagCandidate candidate(String id) { return new RagCandidate(id, "knowledge", "1", id, 1, "v1", List.of(RagRecallMode.VECTOR), null, .8, null, null, .8, RagScoreKind.SEMANTIC_NORMALIZED, "v1", Map.of()); }
    private RagSearchResponse response(String space, List<RagCandidate> candidates) { return new RagSearchResponse(1, "id", space, "v1", RagSearchMode.HYBRID, RagSearchMode.HYBRID, false, List.of(), candidates, List.of()); }
}
