package com.bkanent.common.rpc;

import com.bkanent.common.rag.MemoryRagSearchRequest;
import com.bkanent.common.rag.MemoryRagSearchResponse;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContract;
import com.bkanent.common.rag.RagCredentialContext;

public interface MemoryRagRpcService {
    int CONTRACT_VERSION = RagContract.VERSION;

    MemoryRagSearchResponse search(MemoryRagSearchRequest request, RagAuthEnvelope authentication,
                                   RagCredentialContext credentials);
}
