package com.bkanent.common.rag;

import java.io.Serializable;

/** Current auth facts only; production consumers must verify the auth-service response attestation. */
public record AuthRagPrincipal(Long userId, String roleCode, boolean accountActive, boolean roleActive,
                               long expiresAtEpochSecond, RagAuthEnvelope attestation) implements Serializable {
    private static final long serialVersionUID = 1L;

    public AuthRagPrincipal(Long userId, String roleCode, boolean accountActive, boolean roleActive,
                            long expiresAtEpochSecond) {
        this(userId, roleCode, accountActive, roleActive, expiresAtEpochSecond, null);
    }

    @Override
    public String toString() { return "AuthRagPrincipal[REDACTED]"; }
}
