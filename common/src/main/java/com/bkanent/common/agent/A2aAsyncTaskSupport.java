package com.bkanent.common.agent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A2aAsyncTaskSupport 提供轻量异步 A2A 任务提交与查询能力。
 */
public final class A2aAsyncTaskSupport {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "a2a-async-task");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, AsyncTaskEntry> TASKS = new ConcurrentHashMap<>();
    private static final Map<String, String> IDEMPOTENCY_TASKS = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Consumer<A2aAsyncTaskStatusResponse>>> SUBSCRIBERS =
            new ConcurrentHashMap<>();

    private A2aAsyncTaskSupport() {
    }

    public static A2aAsyncTaskCreateResponse submit(String agentId,
                                                    AgentTaskInvokeRequest request,
                                                    Supplier<AgentTaskInvokeResponse> supplier) {
        String idempotencyKey = buildIdempotencyKey(agentId, request);
        if (idempotencyKey != null) {
            String existingTaskId = IDEMPOTENCY_TASKS.get(idempotencyKey);
            if (existingTaskId != null) {
                AsyncTaskEntry existing = TASKS.get(existingTaskId);
                if (existing != null) {
                    return existing.toCreateResponse();
                }
            }
        }

        String asyncTaskId = UUID.randomUUID().toString();
        AsyncTaskEntry entry = new AsyncTaskEntry(
                agentId,
                asyncTaskId,
                request.sessionId(),
                request.taskId(),
                request.traceId()
        );
        TASKS.put(asyncTaskId, entry);
        if (idempotencyKey != null) {
            IDEMPOTENCY_TASKS.putIfAbsent(idempotencyKey, asyncTaskId);
        }
        publish(entry.toStatusResponse());

        CompletableFuture.runAsync(() -> runTask(entry, supplier), EXECUTOR);
        return entry.toCreateResponse();
    }

    public static A2aAsyncTaskStatusResponse getStatus(String asyncTaskId) {
        AsyncTaskEntry entry = TASKS.get(asyncTaskId);
        return entry == null ? null : entry.toStatusResponse();
    }

    public static void register(String asyncTaskId,
                                String subscriberId,
                                Consumer<A2aAsyncTaskStatusResponse> consumer) {
        SUBSCRIBERS.computeIfAbsent(asyncTaskId, ignored -> new ConcurrentHashMap<>()).put(subscriberId, consumer);
        A2aAsyncTaskStatusResponse current = getStatus(asyncTaskId);
        if (current != null) {
            consumer.accept(current);
        }
    }

    public static void unregister(String asyncTaskId, String subscriberId) {
        Map<String, Consumer<A2aAsyncTaskStatusResponse>> taskSubscribers = SUBSCRIBERS.get(asyncTaskId);
        if (taskSubscribers == null) {
            return;
        }
        taskSubscribers.remove(subscriberId);
        if (taskSubscribers.isEmpty()) {
            SUBSCRIBERS.remove(asyncTaskId);
        }
    }

    private static void runTask(AsyncTaskEntry entry, Supplier<AgentTaskInvokeResponse> supplier) {
        entry.status = "RUNNING";
        publish(entry.toStatusResponse());
        try {
            entry.result = supplier.get();
            entry.status = "COMPLETED";
            publish(entry.toStatusResponse());
        } catch (Exception exception) {
            entry.status = "FAILED";
            entry.errorCode = A2aErrorCodes.A2A_TASK_FAILED;
            entry.errorMessage = exception.getMessage();
            publish(entry.toStatusResponse());
        }
    }

    private static String buildIdempotencyKey(String agentId, AgentTaskInvokeRequest request) {
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            return null;
        }
        return agentId + ":" + request.idempotencyKey().trim();
    }

    private static void publish(A2aAsyncTaskStatusResponse statusResponse) {
        Map<String, Consumer<A2aAsyncTaskStatusResponse>> taskSubscribers = SUBSCRIBERS.get(statusResponse.asyncTaskId());
        if (taskSubscribers == null || taskSubscribers.isEmpty()) {
            return;
        }
        taskSubscribers.values().forEach(consumer -> consumer.accept(statusResponse));
    }

    private static final class AsyncTaskEntry {
        private final String agentId;
        private final String asyncTaskId;
        private final String sessionId;
        private final String taskId;
        private final String traceId;
        private volatile String status;
        private volatile AgentTaskInvokeResponse result;
        private volatile String errorCode;
        private volatile String errorMessage;

        private AsyncTaskEntry(String agentId,
                               String asyncTaskId,
                               String sessionId,
                               String taskId,
                               String traceId) {
            this.agentId = agentId;
            this.asyncTaskId = asyncTaskId;
            this.sessionId = sessionId;
            this.taskId = taskId;
            this.traceId = traceId;
            this.status = "ACCEPTED";
        }

        private A2aAsyncTaskCreateResponse toCreateResponse() {
            return new A2aAsyncTaskCreateResponse(sessionId, taskId, agentId, asyncTaskId, status, traceId);
        }

        private A2aAsyncTaskStatusResponse toStatusResponse() {
            return new A2aAsyncTaskStatusResponse(
                    sessionId,
                    taskId,
                    agentId,
                    asyncTaskId,
                    status,
                    result,
                    errorCode,
                    errorMessage,
                    traceId
            );
        }
    }
}
