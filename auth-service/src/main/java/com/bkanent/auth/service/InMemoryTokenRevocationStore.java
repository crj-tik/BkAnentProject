package com.bkanent.auth.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Local-profile implementation; distributed deployments must use Redis. */
@Component
@ConditionalOnProperty(prefix = "auth.token.revocation", name = "provider", havingValue = "memory")
public class InMemoryTokenRevocationStore implements TokenRevocationStore {

    private final ConcurrentMap<String, Long> revokedUntilMs = new ConcurrentHashMap<>();

    @Override
    public void revoke(String tokenDigest, Duration ttl) {
        if (tokenDigest == null || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        revokedUntilMs.put(tokenDigest, System.currentTimeMillis() + ttl.toMillis());
    }

    @Override
    public boolean isRevoked(String tokenDigest) {
        Long expiresAt = revokedUntilMs.get(tokenDigest);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt <= System.currentTimeMillis()) {
            revokedUntilMs.remove(tokenDigest, expiresAt);
            return false;
        }
        return true;
    }
}
