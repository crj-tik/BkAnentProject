package com.bkanent.rag.rpc;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import com.bkanent.rag.retrieval.RagRetrievalService;
import com.bkanent.rag.security.RagAccessControl;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class RagRetrievalRpcServiceImpl implements RagRetrievalRpcService {
    private final RagAccessControl access;
    private final RagRetrievalService retrieval;
    public RagRetrievalRpcServiceImpl(RagAccessControl access, RagRetrievalService retrieval) {
        this.access = access; this.retrieval = retrieval;
    }
    @Override public RagSearchResponse search(RagSearchRequest request, RagAuthEnvelope authentication,
            RagCredentialContext credentials, RagAuthorizationScope scope) {
        if (request == null) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "request");
        return retrieval.search(request, access.search(request, authentication, credentials, scope));
    }
}
