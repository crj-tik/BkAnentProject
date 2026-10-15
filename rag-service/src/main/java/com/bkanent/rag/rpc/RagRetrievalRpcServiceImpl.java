package com.bkanent.rag.rpc;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import com.bkanent.rag.retrieval.RagRetrievalService;
import com.bkanent.rag.security.RagAccessControl;
import org.apache.dubbo.config.annotation.DubboService;

// fastjson2ctor pin (KI-113 follow-up): Dubbo's built-in fastjson2 writes FieldBased
// JSONB which crashes decoding record list fields; the ctor SPI keeps the strict typed
// contract without class-name tags. See Fastjson2ConstructorSerializationTest.
@DubboService(parameters = {"serialization", "fastjson2ctor"})
public class RagRetrievalRpcServiceImpl implements RagRetrievalRpcService {
    private final RagAccessControl access;
    private final RagRetrievalService retrieval;
    public RagRetrievalRpcServiceImpl(RagAccessControl access, RagRetrievalService retrieval) {
        this.access = access; this.retrieval = retrieval;
    }
    @Override public RagSearchResponse search(RagSearchRequest request, RagAuthEnvelope authentication,
            RagCredentialContext credentials, RagAuthorizationScope scope) {
        if (request == null) throw new RagException(RagErrorCode.INVALID_ARGUMENT, "request");
        var mandatory = access.search(request, authentication, credentials, scope);
        RagSearchResponse response = retrieval.search(request, mandatory);
        if (!mandatory.equals(access.search(request, authentication, credentials, scope))) {
            throw new RagException(RagErrorCode.FORBIDDEN);
        }
        return response;
    }
}
