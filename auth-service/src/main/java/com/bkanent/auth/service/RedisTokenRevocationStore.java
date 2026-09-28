package com.bkanent.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Redis-backed revocation shared by every distributed auth-service instance. */
@Component
@ConditionalOnProperty(prefix = "auth.token.revocation", name = "provider", havingValue = "redis", matchIfMissing = true)
public class RedisTokenRevocationStore implements TokenRevocationStore {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    public RedisTokenRevocationStore(StringRedisTemplate redisTemplate,
                                     @Value("${auth.token.revocation.key-prefix:auth:token:revoked:}") String keyPrefix) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public void revoke(String tokenDigest, Duration ttl) {
        if (tokenDigest == null || ttl == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(keyPrefix + tokenDigest, "1", ttl);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("shared token revocation store is unavailable", exception);
        }
    }

    @Override
    public boolean isRevoked(String tokenDigest) {
        try {
            Boolean revoked = redisTemplate.hasKey(keyPrefix + tokenDigest);
            if (revoked == null) {
                throw new IllegalStateException("shared token revocation store returned no result");
            }
            return revoked;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("shared token revocation store is unavailable", exception);
        }
    }
}
