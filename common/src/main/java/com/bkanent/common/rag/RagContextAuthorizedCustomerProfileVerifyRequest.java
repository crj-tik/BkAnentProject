package com.bkanent.common.rag;

import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthorizationContext;

import java.io.Serializable;

/** Owner-profile verification bound to an Auth-signed execution context. */
public record RagContextAuthorizedCustomerProfileVerifyRequest(
        int contractVersion, RagCustomerProfileVerifyRequest request,
        AuthorizationContext authorizationContext, AuthzFreshness freshness) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagContextAuthorizedCustomerProfileVerifyRequest {
        RagContract.version(contractVersion);
        RagContract.require(request != null, "request");
        RagContract.require(authorizationContext != null && freshness != null, "authorization");
        RagContract.require(authorizationContext.runId() != null && authorizationContext.sessionId() != null,
                "executionBinding");
    }
}
