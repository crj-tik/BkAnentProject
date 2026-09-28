package com.bkanent.auth.service;

import java.time.Duration;

/** Shared storage contract for revoking signed authentication tokens. */
public interface TokenRevocationStore {

    void revoke(String tokenDigest, Duration ttl);

    boolean isRevoked(String tokenDigest);
}
