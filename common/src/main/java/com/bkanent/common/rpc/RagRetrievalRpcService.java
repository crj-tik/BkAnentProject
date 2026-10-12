package com.bkanent.common.rpc;

import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagAuthorizationScope;
import com.bkanent.common.rag.RagContract;
import com.bkanent.common.rag.RagCredentialContext;
import com.bkanent.common.rag.RagSearchRequest;
import com.bkanent.common.rag.RagSearchResponse;

public interface RagRetrievalRpcService {
    int CONTRACT_VERSION = RagContract.VERSION;

    RagSearchResponse search(RagSearchRequest request, RagAuthEnvelope authentication,
                             RagCredentialContext credentials, RagAuthorizationScope scope);
}
