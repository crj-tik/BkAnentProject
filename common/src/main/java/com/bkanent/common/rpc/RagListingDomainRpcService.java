package com.bkanent.common.rpc;

import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContract;
import com.bkanent.common.rag.RagCredentialContext;
import com.bkanent.common.rag.RagIndexStatus;
import com.bkanent.common.rag.RagListingIndexRequest;
import com.bkanent.common.rag.RagListingSearchRequest;
import com.bkanent.common.rag.RagListingSearchResponse;

public interface RagListingDomainRpcService {
    int CONTRACT_VERSION = RagContract.VERSION;

    RagListingSearchResponse search(RagListingSearchRequest request, RagAuthEnvelope authentication,
                                    RagCredentialContext credentials);

    RagIndexStatus index(RagListingIndexRequest request, RagAuthEnvelope authentication,
                         RagCredentialContext credentials);
}
