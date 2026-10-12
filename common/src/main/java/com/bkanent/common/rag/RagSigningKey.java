package com.bkanent.common.rag;

import java.util.Set;

/** Runtime managed key mapping, never serialized or persisted in business requests. */
public record RagSigningKey(String keyId, String callerService, String ownerDomain,
                            Set<RagOperation> allowedOperations, byte[] secret) {
    public RagSigningKey {
        RagContract.text(keyId, "keyId");
        RagContract.text(callerService, "callerService");
        if (ownerDomain != null) RagContract.text(ownerDomain, "ownerDomain");
        RagContract.require(allowedOperations != null && !allowedOperations.isEmpty(), "allowedOperations");
        allowedOperations = Set.copyOf(allowedOperations);
        RagContract.require(secret != null && secret.length >= 32, "secret");
        secret = secret.clone();
    }

    @Override
    public byte[] secret() { return secret.clone(); }

    @Override
    public String toString() { return "RagSigningKey[REDACTED]"; }
}
