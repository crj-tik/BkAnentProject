package com.bkanent.common.rpc;

import com.bkanent.common.rag.*;

public interface A2aBeaconRpcService {
    A2aBeacon issue(A2aBeaconBinding binding, RagCredentialContext credentials, RagAuthEnvelope authentication);
    AuthRagPrincipal resolve(A2aBeaconBinding binding, RagCredentialContext credentials, RagAuthEnvelope authentication);
}
