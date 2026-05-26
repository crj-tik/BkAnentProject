package com.bkanent.agent.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "agent.distributed.rate-limit", name = "provider", havingValue = "memory", matchIfMissing = true)
public class InMemorySupervisorRateLimiter implements SupervisorRateLimiter {

    private final Map<String, FixedWindowCounter> counters = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int limit, long windowMs) {
        FixedWindowCounter counter = counters.computeIfAbsent(key, ignored -> new FixedWindowCounter());
        return counter.tryAcquire(limit, windowMs);
    }

    private static final class FixedWindowCounter {
        private long windowStart;
        private int count;

        private synchronized boolean tryAcquire(int limit, long windowMs) {
            long now = System.currentTimeMillis();
            if (windowStart == 0L || now - windowStart >= windowMs) {
                windowStart = now;
                count = 0;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }
    }
}
