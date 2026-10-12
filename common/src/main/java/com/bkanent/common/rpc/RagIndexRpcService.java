package com.bkanent.common.rpc;

import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContract;
import com.bkanent.common.rag.RagIndexEvent;
import com.bkanent.common.rag.RagIndexStatus;
import com.bkanent.common.rag.RagStatusRequest;

public interface RagIndexRpcService {
    int CONTRACT_VERSION = RagContract.VERSION;

    RagIndexStatus submit(RagIndexEvent event, RagAuthEnvelope authentication);

    RagIndexStatus getStatus(RagStatusRequest request, RagAuthEnvelope authentication);
}
