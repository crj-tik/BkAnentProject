package com.bkanent.common.rpc;

import com.bkanent.common.rag.RagAuthEnvelope;
import com.bkanent.common.rag.RagContextAuthorizedComparisonReportVerifyRequest;

/** Customer-side ownership verification of a Listing comparison report before inquiry association. */
public interface ListingComparisonReportVerifyRpcService {
    ListingComparisonReportVerifyResponse verifyReportOwnerContext(
            RagContextAuthorizedComparisonReportVerifyRequest request, RagAuthEnvelope authentication);
}
