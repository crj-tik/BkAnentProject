package com.bkanent.common.authz;

import java.io.Serial;
import java.io.Serializable;

/** Dedicated service proof bound to the entire RPC request, including credential digest/parent. */
public record CallerProof(String callerService, String audience, AuthzRpcOperation operation,
                          String requestId, String requestHash, String nonce,
                          long issuedAtEpochSecond, long expiresAtEpochSecond,
                          String algorithm, String keyId, String signature) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public CallerProof {
        callerService = AuthzContract.service(callerService);
        audience = AuthzContract.service(audience);
        AuthzContract.require(operation != null);
        requestId = AuthzContract.text(requestId, 128);
        requestHash = AuthzContract.hash(requestHash);
        nonce = AuthzContract.nonce(nonce);
        AuthzContract.period(issuedAtEpochSecond, expiresAtEpochSecond, AuthzDeadlines.REQUEST_SECONDS);
        AuthzContract.signature(algorithm, keyId, signature);
    }

    @Override public String toString() { return "CallerProof[REDACTED]"; }
}
