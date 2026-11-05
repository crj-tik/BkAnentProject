package com.bkanent.common.rag;

import java.io.Serializable;

/** Owner-profile verification selector; the customerId is a business target, never an identity. */
public record RagCustomerProfileVerifyRequest(int contractVersion, String requestId, Long customerId,
                                              String expectedProfileType) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagCustomerProfileVerifyRequest {
        RagContract.version(contractVersion);
        RagContract.text(requestId, "requestId");
        RagContract.require(customerId != null && customerId > 0, "customerId");
        RagContract.require(expectedProfileType != null && !expectedProfileType.isBlank()
                && expectedProfileType.length() <= 32, "expectedProfileType");
    }
}
