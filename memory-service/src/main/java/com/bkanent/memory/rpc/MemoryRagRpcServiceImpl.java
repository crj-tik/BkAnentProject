package com.bkanent.memory.rpc;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.MemoryRagRpcService;
import com.bkanent.memory.service.MemoryRagSearchFacade;
import com.bkanent.memory.service.MemoryRagSecurity;
import org.apache.dubbo.config.annotation.DubboService;

@DubboService
public class MemoryRagRpcServiceImpl implements MemoryRagRpcService {
    private final MemoryRagSecurity security;
    private final MemoryRagSearchFacade facade;
    public MemoryRagRpcServiceImpl(MemoryRagSecurity security, MemoryRagSearchFacade facade) { this.security = security; this.facade = facade; }
    @Override
    public MemoryRagSearchResponse search(MemoryRagSearchRequest request, RagAuthEnvelope authentication, RagCredentialContext credentials) {
        var principal = security.verifyCaller(request, request.search().requestId(), authentication, credentials);
        return facade.search(request, principal, credentials);
    }
}
