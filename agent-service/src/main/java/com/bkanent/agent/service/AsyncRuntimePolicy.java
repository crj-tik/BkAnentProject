package com.bkanent.agent.service;

import java.io.IOException;
import java.net.SocketException;
import java.util.concurrent.TimeoutException;

/**
 * Pure decisions shared by the persisted asynchronous task and workflow dispatchers.
 */
public final class AsyncRuntimePolicy {

    private AsyncRuntimePolicy() {
    }

    public static boolean isRetryable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IOException
                    || current instanceof SocketException
                    || current instanceof TimeoutException
                    || current instanceof java.util.concurrent.RejectedExecutionException) {
                return true;
            }
            String type = current.getClass().getSimpleName().toLowerCase();
            if (type.contains("timeout") || type.contains("connect")
                    || type.contains("unavailable") || type.contains("retryable")
                    || type.contains("resourceaccess")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static boolean shouldRetry(int attemptCount, int maxAttempts, Throwable failure) {
        return isRetryable(failure) && attemptCount < Math.max(1, maxAttempts);
    }

    public static String failureCode(Throwable failure) {
        return isRetryable(failure) ? "TRANSIENT_EXECUTION_ERROR" : "PERMANENT_EXECUTION_ERROR";
    }

    public static boolean isLeaseStale(Long leaseUntilMs, long nowMs) {
        return leaseUntilMs == null || leaseUntilMs <= nowMs;
    }
}
