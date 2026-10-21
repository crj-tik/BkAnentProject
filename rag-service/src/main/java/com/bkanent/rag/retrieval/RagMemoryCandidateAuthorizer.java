package com.bkanent.rag.retrieval;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagCandidateAuthorizationRpcService;
import com.bkanent.rag.config.RagMemoryCandidateAuthorizationProperties;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Calls Memory with identity-only candidates before RAG can send any body to a reranker. */
@Component
public class RagMemoryCandidateAuthorizer implements RagRetrievalService.CandidateAuthorizer {
    private final RagMemoryCandidateAuthorizationProperties properties;

    @DubboReference(check = false, init = false, timeout = 3000, retries = 0)
    private RagCandidateAuthorizationRpcService memory;

    public RagMemoryCandidateAuthorizer(RagMemoryCandidateAuthorizationProperties properties) {
        this.properties = properties;
    }

    @Override public Set<String> authorize(RagCandidateAuthorizationRequest request, MemoryRagAuthorizationScope scope) {
        if (request == null || scope == null || properties.keyId() == null || properties.keyId().isBlank()
                || properties.secret() == null || properties.secret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "memoryAuthorization");
        }
        long now = Instant.now().getEpochSecond();
        RagAuthEnvelope unsigned = new RagAuthEnvelope(RagContract.VERSION, properties.keyId(), "memory-service",
                "rag-service", RagOperation.AUTHORIZE_CANDIDATES, request.search().requestId(),
                RagCanonicalHash.requestHash(request), RagActorMode.SERVICE, "rag-service", now, now + 10,
                null, null);
        RagAuthEnvelope authentication = RagRequestSigner.sign(unsigned, properties.secret().getBytes(StandardCharsets.UTF_8));
        RagCandidateAuthorizationResponse response;
        try { response = memory.authorize(request, authentication, scope); }
        catch (RagException failure) { throw failure; }
        catch (RuntimeException failure) { throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "memoryAuthorization"); }
        if (response == null || response.contractVersion() != RagContract.VERSION
                || !request.search().requestId().equals(response.requestId())) {
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "memoryAuthorization");
        }
        Set<String> requested = request.candidates().stream().map(RagCandidateReference::documentId).collect(java.util.stream.Collectors.toSet());
        Set<String> permitted = new HashSet<>(response.authorizedDocumentIds());
        if (!requested.containsAll(permitted)) throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE, "memoryAuthorization");
        return Set.copyOf(permitted);
    }
}
