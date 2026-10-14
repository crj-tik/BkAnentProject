package com.bkanent.common.rpc;

import com.bkanent.common.rag.AuthRagPrincipal;
import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContract;
import com.bkanent.common.rag.RagCredentialContext;
import com.bkanent.common.rag.RagException;

/** Minimal RAG-specific principal contract; existing AuthPermissionRpcService remains unchanged. */
public interface RagPrincipalRpcService {
    int CONTRACT_VERSION = RagContract.VERSION;

    /** Request hash equals credentialDigest(credentials); response hash equals principalHash(principal). */
    AuthRagPrincipal resolveRagPrincipal(RagCredentialContext credentials, RagAuthEnvelope authentication) throws RagException;
}
