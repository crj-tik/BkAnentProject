package com.bkanent.rag.security.http;

import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;

import java.time.Duration;

/** Absolute monotonic deadline. Open per stage; propagate explicitly to executor workers. */
public final class RagHttpDeadline implements AutoCloseable {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private final Long previous;

    private RagHttpDeadline(long deadline) {
        previous = CURRENT.get();
        CURRENT.set(previous == null ? deadline : Math.min(previous, deadline));
    }

    public static RagHttpDeadline open(Duration budget) {
        if (budget == null || budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(20)) > 0) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT, "deadline");
        }
        return new RagHttpDeadline(System.nanoTime() + budget.toNanos());
    }

    public static RagHttpDeadline until(long deadlineNanos) {
        return new RagHttpDeadline(deadlineNanos);
    }

    public static long capture(Duration maximum) {
        long limit = System.nanoTime() + maximum.toNanos();
        Long current = CURRENT.get();
        return current == null ? limit : Math.min(current, limit);
    }

    public static long remainingNanos(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw timeout();
        }
        return remaining;
    }

    static RagException timeout() {
        return new RagException(RagErrorCode.TIMEOUT);
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
