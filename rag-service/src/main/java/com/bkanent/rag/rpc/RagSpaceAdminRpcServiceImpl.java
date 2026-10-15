package com.bkanent.rag.rpc;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagSpaceAdminRpcService;
import com.bkanent.rag.config.ManagedRagSpaceRegistry;
import com.bkanent.rag.index.RagIndexService;
import com.bkanent.rag.security.RagAccessControl;
import org.apache.dubbo.config.annotation.DubboService;

// fastjson2ctor pin (KI-113 follow-up): Dubbo's built-in fastjson2 writes FieldBased
// JSONB which crashes decoding record list fields; the ctor SPI keeps the strict typed
// contract without class-name tags. See Fastjson2ConstructorSerializationTest.
@DubboService(parameters = {"serialization", "fastjson2ctor"})
public class RagSpaceAdminRpcServiceImpl implements RagSpaceAdminRpcService {
    private final RagAccessControl access;
    private final ManagedRagSpaceRegistry registry;
    private final RagIndexService index;
    public RagSpaceAdminRpcServiceImpl(RagAccessControl access, ManagedRagSpaceRegistry registry, RagIndexService index) {
        this.access = access; this.registry = registry; this.index = index;
    }
    @Override public RagSpaceInspection inspect(RagInspectRequest request, RagAuthEnvelope authentication, RagCredentialContext credentials) {
        if (request == null) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "request");
        access.admin(request, request.spaceId(), request.requestId(), RagOperation.INSPECT, authentication, credentials);
        return registry.inspect(request.requestId(), request.spaceId());
    }
    @Override public RagSpaceInspection provision(RagProvisionRequest request, RagAuthEnvelope authentication, RagCredentialContext credentials) {
        if (request == null) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "request");
        access.admin(request, request.spaceId(), request.requestId(), RagOperation.PROVISION, authentication, credentials);
        return registry.provision(request);
    }
    @Override public RagIndexStatus replay(RagReplayRequest request, RagAuthEnvelope authentication, RagCredentialContext credentials) {
        if (request == null) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "request");
        access.admin(request, request.spaceId(), request.requestId(), RagOperation.REPLAY, authentication, credentials);
        return index.replay(request);
    }
}
