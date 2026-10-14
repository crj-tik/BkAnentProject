package com.bkanent.common.rag;

import java.io.Serializable;

/** Dedicated ephemeral credentials. Exactly one variant; never model/config/checkpoint data. */
public record RagCredentialContext(String accessToken, String beacon) implements Serializable {
    private static final long serialVersionUID = 1L;
    public RagCredentialContext(String accessToken) { this(accessToken, null); }
    public static RagCredentialContext beacon(String value) { return new RagCredentialContext(null, value); }
    public RagCredentialContext {
        boolean tokenPresent = accessToken != null && !accessToken.isBlank();
        boolean beaconPresent = beacon != null && !beacon.isBlank();
        RagContract.require(tokenPresent != beaconPresent, "credentials");
        RagContract.require(accessToken == null || tokenPresent && accessToken.length() <= 16384, "credentials");
        RagContract.require(beacon == null || beaconPresent && beacon.length() <= 512, "credentials");
    }
    @Override public String toString() { return "RagCredentialContext[REDACTED]"; }
}
