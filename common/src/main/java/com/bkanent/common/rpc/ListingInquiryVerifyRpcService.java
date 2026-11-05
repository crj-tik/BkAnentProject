package com.bkanent.common.rpc;

import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedListingInquiryVerifyRequest;

/** Customer-side verification of an on-sale listing before registering an inquiry. */
public interface ListingInquiryVerifyRpcService {
    ListingInquiryVerifyResponse verifyInquiryListingContext(
            RagContextAuthorizedListingInquiryVerifyRequest request, RagAuthEnvelope authentication);
}
