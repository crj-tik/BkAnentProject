package com.bkanent.common.memory;

import java.io.Serializable;

/**
 * Per-request identity envelope bound to one v2 operation. requestId plus bodyHash form
 * the request hash identity used for idempotency; the signature itself is issued and
 * verified outside this shape (3.x). Not a bearer token: it never carries credentials.
 */
public record MemoryRequestEnvelope(String operation, String caller, String audience,
                                     String requestId, String bodyHash, int contractVersion)
        implements Serializable {
    private static final long serialVersionUID = 1L;

    public MemoryRequestEnvelope {
        MemoryV2Contract.requireEnvelopeFields(operation, caller, audience, requestId, bodyHash, contractVersion);
    }

    /** Stable hash identity for idempotency and replay correlation. */
    public String requestIdentity() {
        return requestId + ":" + bodyHash;
    }
}
