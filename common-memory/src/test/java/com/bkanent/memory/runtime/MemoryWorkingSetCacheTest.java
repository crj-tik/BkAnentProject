package com.bkanent.memory.runtime;

import com.bkanent.common.memory.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryWorkingSetCacheTest {
    @Test void capacityAndExpiryAreBoundedAndEvictedOnAccess() {
        var clock = new MutableClock(Instant.parse("2026-10-20T00:00:00Z"));
        var cache = new MemoryWorkingSetCache(1, 4096, 1000, clock);
        var first = context("one"); var second = context("two");
        cache.put(snapshot(first, "policy-1"));
        cache.put(snapshot(second, "policy-2"));
        assertNull(cache.get(first));
        assertEquals(snapshot(second, "policy-2"), cache.get(second));
        clock.advanceMillis(1001);
        assertNull(cache.get(second));
        assertEquals(0, cache.size());
    }

    @Test void cachedValueRetainsPolicyVersionForRequiredBoundaryRecheck() {
        var cache = new MemoryWorkingSetCache(2, 4096, 60_000, Clock.systemUTC());
        var context = context("same");
        cache.put(snapshot(context, "policy-before"));
        cache.put(snapshot(context, "policy-after"));
        assertEquals("policy-after", cache.get(context).version().policyVersion());
    }

    private static MemoryContextKey context(String id) { return new MemoryContextKey("owner", "supervisor", "agent", id); }
    private static MemoryWorkingSetResponse snapshot(MemoryContextKey key, String policy) {
        return new MemoryWorkingSetResponse(key, new MemoryConversationVersion(0, 0, policy), List.of(), List.of(), List.of());
    }

    private static final class MutableClock extends Clock {
        private long millis;
        private MutableClock(Instant initial) { millis = initial.toEpochMilli(); }
        private void advanceMillis(long amount) { millis += amount; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
