package com.bkanent.memory.runtime;

import com.bkanent.common.memory.*;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.LinkedHashMap;

/** Bounded process-local material; neither a durable source nor an authorization cache. */
public final class MemoryWorkingSetCache {
    private record Entry(MemoryWorkingSetResponse value, long expiresAt, int bytes) { }
    private final LinkedHashMap<MemoryContextKey,Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final int capacity;
    private final long maxBytes;
    private final long ttlMs;
    private final Clock clock;
    private long bytes;
    public MemoryWorkingSetCache(int capacity, long maxBytes, long ttlMs, Clock clock) {
        if (capacity < 1 || capacity > 10000 || maxBytes < 1024 || ttlMs < 1 || ttlMs > 300_000) throw new IllegalArgumentException("invalid memory cache bounds");
        this.capacity = capacity; this.maxBytes = maxBytes; this.ttlMs = ttlMs; this.clock = clock;
    }
    public synchronized MemoryWorkingSetResponse get(MemoryContextKey key) {
        var entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAt <= clock.millis()) { remove(key); return null; }
        return entry.value;
    }
    public synchronized void put(MemoryWorkingSetResponse value) {
        int weight;
        try { weight = JsonMapper.builder().findAndAddModules().build().writeValueAsBytes(value).length; }
        catch (Exception exception) { throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
        if (weight > Math.min(maxBytes, 1024 * 1024)) { remove(value.contextKey()); throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED); }
        evictExpired();
        remove(value.contextKey());
        entries.put(value.contextKey(), new Entry(value, clock.millis() + ttlMs, weight)); bytes += weight;
        while (entries.size() > capacity || bytes > maxBytes) remove(entries.keySet().iterator().next());
    }
    public synchronized void remove(MemoryContextKey key) { var previous = entries.remove(key); if (previous != null) bytes -= previous.bytes; }
    public synchronized void evictExpired() {
        entries.entrySet().removeIf(entry -> { if (entry.getValue().expiresAt <= clock.millis()) { bytes -= entry.getValue().bytes; return true; } return false; });
    }
    public synchronized int size() { evictExpired(); return entries.size(); }
}
