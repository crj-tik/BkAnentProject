package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.a2a.core.server.A2aServerExecutorProvider;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.*;

/** Supported starter SPI; capture occurs at actual SDK CompletableFuture dispatch. */
public final class A2aContextExecutorProvider implements A2aServerExecutorProvider {
    private final ExecutorService pool = new ThreadPoolExecutor(16, 16, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService wrapper = new AbstractExecutorService() {
        @Override public void execute(Runnable command) {
            var lease = A2aCredentialContext.snapshot();
            boolean reserved = A2aCredentialContext.markDispatched(lease);
            try {
                pool.execute(() -> {
                    try (var ignored = A2aCredentialContext.open(lease)) {
                        command.run();
                    } finally {
                        if (reserved) A2aCredentialContext.releaseDispatchedTask(lease);
                    }
                });
            } catch (RuntimeException failure) {
                if (reserved) A2aCredentialContext.releaseDispatchedTask(lease);
                throw failure;
            }
        }
        @Override public void shutdown() { pool.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return pool.shutdownNow(); }
        @Override public boolean isShutdown() { return pool.isShutdown(); }
        @Override public boolean isTerminated() { return pool.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException { return pool.awaitTermination(timeout, unit); }
    };
    @Override public ExecutorService getA2aServerExecutor() { return wrapper; }
    @PreDestroy public void close() { pool.shutdownNow(); }
}
