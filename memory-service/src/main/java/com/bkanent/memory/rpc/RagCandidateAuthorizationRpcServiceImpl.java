package com.bkanent.memory.rpc;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagCandidateAuthorizationRpcService;
import com.bkanent.memory.service.MemoryRagCandidateAuthorizationService;
import com.bkanent.memory.service.MemoryRagSecurity;
import org.apache.dubbo.config.annotation.DubboService;

/** RAG callback. It returns only permitted IDs and never exposes Memory source bodies. */
@DubboService(parameters = {"serialization", "fastjson2ctor"})
public class RagCandidateAuthorizationRpcServiceImpl implements RagCandidateAuthorizationRpcService {
    private final MemoryRagSecurity security;
    private final MemoryRagCandidateAuthorizationService authorization;

    public RagCandidateAuthorizationRpcServiceImpl(MemoryRagSecurity security,
            MemoryRagCandidateAuthorizationService authorization) {
        this.security = security;
        this.authorization = authorization;
    }

    @Override public RagCandidateAuthorizationResponse authorize(RagCandidateAuthorizationRequest request,
            RagAuthEnvelope authentication, MemoryRagAuthorizationScope scope) {
        security.verifyCandidateCaller(request, authentication, scope);
        return authorization.authorize(request, scope);
    }
}
