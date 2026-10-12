package com.bkanent.agent.milvus.memory;

import com.bkanent.agent.milvus.core.model.MilvusCollectionInitRequest;
import com.bkanent.agent.milvus.core.model.MilvusSearchResult;
import com.bkanent.agent.rag.AgentRagCompatibility;
import com.bkanent.agent.security.AgentRagCredentialLeaseStore;
import com.bkanent.agent.security.AgentRagRequestAuthentication;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

/** Historical service name retained as a pure authorized RPC compatibility wrapper. */
@Service
public class AgentMemoryMilvusService {
    private final ObjectProvider<RagRetrievalRpcService> retrieval;
    private final ObjectProvider<MemoryRagRpcService> memory;
    private final ObjectProvider<RagSpaceAdminRpcService> admin;
    private final AgentRagCredentialLeaseStore credentials;
    private final AgentRagRequestAuthentication authentication;

    public AgentMemoryMilvusService(ObjectProvider<RagRetrievalRpcService> retrieval,
                                    ObjectProvider<MemoryRagRpcService> memory,
                                    ObjectProvider<RagSpaceAdminRpcService> admin,
                                    AgentRagCredentialLeaseStore credentials, AgentRagRequestAuthentication authentication) {
        this.retrieval = retrieval; this.memory = memory; this.admin = admin;
        this.credentials = credentials; this.authentication = authentication;
    }
    public List<MilvusSearchResult> searchKnowledge(String userId, String collectionName, String query, int topK) {
        String space = AgentRagCompatibility.space(collectionName, "supervisor.knowledge");
        if ("listing.knowledge".equals(space)) throw new RagException(RagErrorCode.FORBIDDEN);
        var request = AgentRagCompatibility.knowledge(space, query, topK);
        var token = credentials.credentials(userId);
        try {
            if (space.startsWith("memory.")) {
                var provider = memory.getIfAvailable();
                if (provider == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
                var domainRequest = new MemoryRagSearchRequest(request, topK, null, null);
                var response = provider.search(domainRequest, authentication.user(domainRequest, request.requestId(),
                        "memory-service", RagOperation.SEARCH, userId, token), token);
                // Only domain-final-checked matches, never raw retrieval candidates.
                return response.matches().stream().map(match -> AgentRagCompatibility.result(space, match.candidate(), response.retrieval())).toList();
            }
            var scope = authentication.supervisorScope(request, userId);
            var provider = retrieval.getIfAvailable();
            if (provider == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            var response = provider.search(request, authentication.user(request, request.requestId(), "rag-service",
                    RagOperation.SEARCH, userId, token), token, scope);
            return response.candidates().stream().map(candidate -> AgentRagCompatibility.result(space, candidate, response)).toList();
        } catch (RagException exception) { throw exception; }
        catch (RuntimeException exception) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
    public void initializeMemoryCollection(String userId, MilvusCollectionInitRequest legacy) {
        String space = AgentRagCompatibility.space(legacy.collectionName(), "supervisor.knowledge");
        if (legacy.dimension() == null || legacy.dimension() < 1) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "dimension");
        var token = credentials.credentials(userId);
        var provider = admin.getIfAvailable();
        if (provider == null) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        try {
            var inspect = new RagInspectRequest(1, UUID.randomUUID().toString(), space);
            var inspection = provider.inspect(inspect, authentication.user(inspect, inspect.requestId(), "rag-service",
                    RagOperation.INSPECT, userId, token), token);
            if (inspection == null || !space.equals(inspection.spaceId())) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
            if (inspection.dimension() != legacy.dimension()) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "dimension");
            var provision = new RagProvisionRequest(1, UUID.randomUUID().toString(), space, inspection.schemaVersion(), legacy.dimension());
            var provisioned = provider.provision(provision, authentication.user(provision, provision.requestId(), "rag-service",
                    RagOperation.PROVISION, userId, token), token);
            if (provisioned == null || !space.equals(provisioned.spaceId()) || !provisioned.ready()
                    || provisioned.dimension() != legacy.dimension()) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (RagException exception) { throw exception; }
        catch (RuntimeException exception) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE); }
    }
}
