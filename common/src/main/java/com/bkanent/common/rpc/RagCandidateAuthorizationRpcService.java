package com.bkanent.common.rpc;

import com.bkanent.common.rag.MemoryRagAuthorizationScope;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagCandidateAuthorizationRequest;
import com.bkanent.common.rag.RagCandidateAuthorizationResponse;
import com.bkanent.common.rag.RagContract;

/** RAG-to-Memory callback: authorize metadata-only candidate identities before any body is reranked. */
public interface RagCandidateAuthorizationRpcService {
    int CONTRACT_VERSION = RagContract.VERSION;

    RagCandidateAuthorizationResponse authorize(RagCandidateAuthorizationRequest request,
            RagAuthEnvelope authentication, MemoryRagAuthorizationScope scope);
}
