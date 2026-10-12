package com.bkanent.common.rag;

import java.io.Serializable;

/** Ephemeral dedicated RPC credential; never persist, log, or expose as a model schema. */
public record RagCredentialContext(String accessToken) implements Serializable {
    private static final long serialVersionUID = 1L;

    public RagCredentialContext {
        RagContract.require(accessToken != null && !accessToken.isBlank() && accessToken.length() <= 16384, "credentials");
    }

    @Override
    public String toString() { return "RagCredentialContext[REDACTED]"; }
}
