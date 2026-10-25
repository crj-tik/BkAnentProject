package com.bkanent.auth.authorization.redis;

import com.bkanent.auth.config.AuthAuthorizationRedisProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisActiveAuthorizationActorsTest {
    private final Instant now = Instant.parse("2026-10-23T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final AuthAuthorizationRedisProperties settings = settings();
    private final RedisActiveAuthorizationActors index = new RedisActiveAuthorizationActors(redis, settings, clock);

    @Test
    void registerUsesAbsoluteRunDeadlineCappedAt300() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        assertTrue(index.register(7, now.plusSeconds(900)));
        verify(redis).execute(any(RedisScript.class), eq(List.of("authz:{active}:actors")),
                eq("REGISTER"), eq("7"), eq(Long.toString(now.plusSeconds(300).getEpochSecond())), eq("1000"));
    }

    @Test
    void expiredActorsAndUnauthenticatedIdsDoNotTouchRedis() {
        assertFalse(index.register(0, now.plusSeconds(20)));
        assertFalse(index.register(7, now));
        assertFalse(index.register(7, null));
        verifyNoInteractions(redis);
    }

    @Test
    void boundedPageReturnsOnlyLiveActorsAndNoRunIdentity() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(
                List.of("7", Long.toString(now.plusSeconds(100).getEpochSecond()),
                        "8", Long.toString(now.minusSeconds(1).getEpochSecond())));
        assertEquals(List.of(new RedisActiveAuthorizationActors.Actor(7, now.plusSeconds(100))), index.page(0));
        verify(redis).execute(any(RedisScript.class), eq(List.of("authz:{active}:actors")),
                eq("PAGE"), eq("0"), eq("100"), eq("1000"));
    }

    @Test
    void unknownRegistrationOutcomeAndCapacityRejectionAreExplicit() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null, 0L);
        assertThrows(IllegalStateException.class, () -> index.register(7, now.plusSeconds(20)));
        assertFalse(index.register(7, now.plusSeconds(20)));
    }

    @Test
    void malformedOrOversizedPageIsRejected() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of("7"));
        assertThrows(IllegalStateException.class, () -> index.page(0));
        assertThrows(IllegalArgumentException.class, () -> index.page(1000));
        assertThrows(IllegalArgumentException.class, () -> index.page(-1));
    }

    @Test
    void indexNetworkOperationsCannotRunInsideSqlTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> index.register(7, now.plusSeconds(20)));
            assertThrows(IllegalStateException.class, () -> index.page(0));
            verifyNoInteractions(redis);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }

    private static AuthAuthorizationRedisProperties settings() {
        var settings = new AuthAuthorizationRedisProperties();
        settings.setEnabled(true);
        settings.setProtectedWriterConfigured(true);
        return settings;
    }
}
