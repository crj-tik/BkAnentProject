package com.bkanent.agent.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncRuntimePolicyTest {

    @Test
    void retriesTransientFailuresOnlyBeforeAttemptLimit() {
        assertTrue(AsyncRuntimePolicy.isRetryable(new TimeoutException("provider timeout")));
        assertTrue(AsyncRuntimePolicy.shouldRetry(1, 3, new IOException("connection reset")));
        assertFalse(AsyncRuntimePolicy.shouldRetry(3, 3, new IOException("connection reset")));
        assertFalse(AsyncRuntimePolicy.shouldRetry(1, 3, new IllegalArgumentException("invalid request")));
    }

    @Test
    void identifiesStaleAndLiveLeases() {
        long now = System.currentTimeMillis();

        assertTrue(AsyncRuntimePolicy.isLeaseStale(null, now));
        assertTrue(AsyncRuntimePolicy.isLeaseStale(now - Duration.ofSeconds(1).toMillis(), now));
        assertFalse(AsyncRuntimePolicy.isLeaseStale(now + Duration.ofSeconds(30).toMillis(), now));
    }

    @Test
    void storesSafeFailureClassification() {
        assertTrue(AsyncRuntimePolicy.failureCode(new TimeoutException("secret token"))
                .startsWith("TRANSIENT_"));
        assertTrue(AsyncRuntimePolicy.failureCode(new IllegalStateException("provider rejected"))
                .startsWith("PERMANENT_"));
    }
}
