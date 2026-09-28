package com.bkanent.agent.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class AsyncRuntimeLeaseHeartbeatTest {

    @Test
    void periodicallyRenewsAndStopsWhenClosed() throws Exception {
        CountDownLatch renewed = new CountDownLatch(1);
        AtomicInteger renewCount = new AtomicInteger();

        AsyncRuntimeLeaseHeartbeat.LeaseHeartbeat heartbeat = AsyncRuntimeLeaseHeartbeat.start(
                "test", "lease-1", 1L, () -> {
                    renewCount.incrementAndGet();
                    renewed.countDown();
                    return true;
                });

        assertThat(renewed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(renewCount.get()).isPositive();
        assertThatNoException().isThrownBy(heartbeat::close);
    }
}
