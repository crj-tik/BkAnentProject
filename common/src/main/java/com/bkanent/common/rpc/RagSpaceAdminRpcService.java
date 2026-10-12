package com.bkanent.common.rpc;

import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContract;
import com.bkanent.common.rag.RagCredentialContext;
import com.bkanent.common.rag.RagIndexStatus;
import com.bkanent.common.rag.RagInspectRequest;
import com.bkanent.common.rag.RagProvisionRequest;
import com.bkanent.common.rag.RagReplayRequest;
import com.bkanent.common.rag.RagSpaceInspection;

public interface RagSpaceAdminRpcService {
    int CONTRACT_VERSION = RagContract.VERSION;

    RagSpaceInspection inspect(RagInspectRequest request, RagAuthEnvelope authentication, RagCredentialContext credentials);

    RagSpaceInspection provision(RagProvisionRequest request, RagAuthEnvelope authentication, RagCredentialContext credentials);

    RagIndexStatus replay(RagReplayRequest request, RagAuthEnvelope authentication, RagCredentialContext credentials);
}
