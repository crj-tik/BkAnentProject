package com.bkanent.rag.rpc;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import com.bkanent.rag.retrieval.RagRetrievalService;
import com.bkanent.rag.security.RagAccessControl;
import org.apache.dubbo.config.annotation.DubboService;

// hessian2 pin (KI-113 follow-up): fastjson2 2.0.52+ JSONB crashes on FieldBased record
// list fields through Dubbo's security handler; see RagFastjson2JsonbRoundTripTest.
@DubboService(parameters = {"serialization", "hessian2"})
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
