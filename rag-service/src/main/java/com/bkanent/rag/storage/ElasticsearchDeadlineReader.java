package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import com.bkanent.rag.security.http.RagHttpDeadline;
import jakarta.annotation.PreDestroy;
import org.elasticsearch.client.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the existing protected ES transport, cancelling the actual request including body consumption. */
@Component
public final class ElasticsearchDeadlineReader {
    private final RestClient client;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "rag-es-cancel"); thread.setDaemon(true); return thread;
    });
    public ElasticsearchDeadlineReader(RestClient client) {
        this.client = client;
        timer.setRemoveOnCancelPolicy(true);
    }

    public Response read(Request request, Duration budget, int maxResponseBytes) {
        request.setOptions(RequestOptions.DEFAULT.toBuilder().setHttpAsyncResponseConsumerFactory(
                new HttpAsyncResponseConsumerFactory.HeapBufferedResponseConsumerFactory(maxResponseBytes)));
        long deadline = RagHttpDeadline.capture(budget);
        CompletableFuture<Response> result = new CompletableFuture<>();
        AtomicBoolean expired = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Cancellable> operationRef = new java.util.concurrent.atomic.AtomicReference<>();
        ScheduledFuture<?> cancellation = timer.schedule(() -> {
            expired.set(true);
            Cancellable current = operationRef.get();
            if (current != null) current.cancel();
            result.completeExceptionally(new RagException(RagErrorCode.TIMEOUT));
        }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        Cancellable operation;
        try { operation = client.performRequestAsync(request, new ResponseListener() {
            @Override public void onSuccess(Response response) { if (!expired.get()) result.complete(response); }
            @Override public void onFailure(Exception failure) { if (!expired.get()) result.completeExceptionally(failure); }
        }); }
        catch (RuntimeException failure) { cancellation.cancel(false); throw failure; }
        operationRef.set(operation);
        if (expired.get()) operation.cancel();
        try {
            return result.get(RagHttpDeadline.remainingNanos(deadline), TimeUnit.NANOSECONDS);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); expired.set(true); operation.cancel(); throw new RagException(RagErrorCode.TIMEOUT);
        }
        catch (TimeoutException | RagException failure) {
            expired.set(true); operation.cancel(); throw new RagException(RagErrorCode.TIMEOUT);
        }
        catch (ExecutionException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof RagException error) throw error;
                if (cause instanceof ResponseException response) {
                    int status = response.getResponse().getStatusLine().getStatusCode();
                    if (status == 401) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
                    if (status == 403) throw new RagException(RagErrorCode.FORBIDDEN);
                }
            }
            throw new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        finally { cancellation.cancel(false); }
    }
    @PreDestroy public void close() { timer.shutdownNow(); }
}
