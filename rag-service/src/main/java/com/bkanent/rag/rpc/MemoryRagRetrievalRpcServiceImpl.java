package com.bkanent.rag.rpc;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.MemoryRagRetrievalRpcService;
import com.bkanent.rag.retrieval.RagRetrievalService;
import com.bkanent.rag.security.RagAccessControl;
import org.apache.dubbo.config.annotation.DubboService;

/** Dedicated internal memory-search route with a non-user service signature and mandatory memory scope. */
@DubboService(parameters = {"serialization", "fastjson2ctor"})
public class MemoryRagRetrievalRpcServiceImpl implements MemoryRagRetrievalRpcService {
    private final RagAccessControl access;
    private final RagRetrievalService retrieval;

    public MemoryRagRetrievalRpcServiceImpl(RagAccessControl access, RagRetrievalService retrieval) {
        this.access = access;
        this.retrieval = retrieval;
    }

    @Override public RagSearchResponse search(RagSearchRequest request, RagAuthEnvelope authentication,
            MemoryRagAuthorizationScope scope) {
        if (request == null) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "request");
        var mandatory = access.searchMemory(request.normalized(), authentication, scope);
        RagSearchResponse response = retrieval.search(request, mandatory, scope);
        if (!mandatory.equals(access.searchMemory(request.normalized(), authentication, scope))) {
            throw new RagException(RagErrorCode.FORBIDDEN);
        }
        return response;
    }
}
