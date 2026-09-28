package com.bkanent.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Renews persisted async-work leases while their owning worker is alive. */
public final class AsyncRuntimeLeaseHeartbeat {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncRuntimeLeaseHeartbeat.class);
    private static final ScheduledThreadPoolExecutor SCHEDULER = createScheduler();

    private AsyncRuntimeLeaseHeartbeat() {
    }

    public static LeaseHeartbeat start(String resourceType,
                                       String resourceId,
                                       long leaseTimeoutSeconds,
                                       BooleanSupplier renewLease) {
        long leaseMillis = TimeUnit.SECONDS.toMillis(Math.max(1L, leaseTimeoutSeconds));
        long intervalMillis = Math.max(100L, leaseMillis / 3L);
        AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();
        Runnable renewal = () -> {
            try {
                if (!renewLease.getAsBoolean()) {
                    LOGGER.warn("Lost async {} lease for {}", resourceType, resourceId);
                    ScheduledFuture<?> task = taskRef.get();
                    if (task != null) {
                        task.cancel(false);
                    }
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Unable to renew async {} lease for {}", resourceType, resourceId, exception);
            }
        };
        ScheduledFuture<?> task = SCHEDULER.scheduleAtFixedRate(
                renewal, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        taskRef.set(task);
        return new LeaseHeartbeat(task);
    }

    private static ScheduledThreadPoolExecutor createScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, runnable -> {
            Thread thread = new Thread(runnable, "async-runtime-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    public static final class LeaseHeartbeat implements AutoCloseable {
        private final ScheduledFuture<?> task;

        private LeaseHeartbeat(ScheduledFuture<?> task) {
            this.task = task;
        }

        @Override
        public void close() {
            task.cancel(false);
        }
    }
}
