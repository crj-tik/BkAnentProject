package com.bkanent.common.rpc;

import com.bkanent.common.rag.MemoryRagAuthorizationScope;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContract;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rag.RagSearchRequest;
import com.bkanent.common.rag.RagSearchResponse;

/** Versioned internal Memory v2 search path; legacy RAG APIs retain their existing semantics. */
public interface MemoryRagRetrievalRpcService {
    int CONTRACT_VERSION = RagContract.VERSION;

    RagSearchResponse search(RagSearchRequest request, RagAuthEnvelope authentication,
            MemoryRagAuthorizationScope scope) throws RagException;
}
