package com.bkanent.auth;

import com.bkanent.auth.service.RedisTokenRevocationStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisTokenRevocationStoreTest {

    @Test
    @SuppressWarnings("unchecked")
    void storesOnlyDigestWithExpiryAndReadsFromSharedRedisKey() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(redisTemplate.hasKey("test:revoked:digest")).thenReturn(true);
        RedisTokenRevocationStore store = new RedisTokenRevocationStore(redisTemplate, "test:revoked:");

        store.revoke("digest", Duration.ofMinutes(2));

        verify(values).set("test:revoked:digest", "1", Duration.ofMinutes(2));
        assertThat(store.isRevoked("digest")).isTrue();
        verify(redisTemplate).hasKey("test:revoked:digest");
    }

    @Test
    void failsClosedWhenRedisCannotAnswerRevocationCheck() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey("auth:token:revoked:digest"))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        RedisTokenRevocationStore store = new RedisTokenRevocationStore(redisTemplate, "auth:token:revoked:");

        assertThatThrownBy(() -> store.isRevoked("digest"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared token revocation store is unavailable");
    }
}
