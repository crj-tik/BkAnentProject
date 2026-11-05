package com.bkanent.common.rpc;

import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedCustomerProfileVerifyRequest;

/** Listing-side verification of an owner profile before registering a listing. */
public interface CustomerProfileVerifyRpcService {
    CustomerProfileVerifyResponse verifyOwnerProfileContext(
            RagContextAuthorizedCustomerProfileVerifyRequest request, RagAuthEnvelope authentication);
}
