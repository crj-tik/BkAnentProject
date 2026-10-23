package com.bkanent.common.a2a;

import com.bkanent.common.memory.MemoryV2RpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bkanent.memory.runtime.MemoryWorkingSetCache;
import org.apache.dubbo.config.ReferenceConfig;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Creates fixed-identity hooks for the domain's actual ReactAgent. */
public final class A2aMemoryHookFactory implements AutoCloseable {
    private final A2aMemoryProperties properties;
    private final Supplier<MemoryV2RpcService> memoryRpcProvider;
    private final MemoryWorkingSetCache cache;
    private final ObjectMapper mapper;
    private final ExecutorService summaries;

    public A2aMemoryHookFactory(A2aMemoryProperties properties, ReferenceConfig<MemoryV2RpcService> memoryReference,
                                MemoryWorkingSetCache cache, ObjectMapper mapper) {
        this(properties, memoryReference::get, cache, mapper);
    }

    public A2aMemoryHookFactory(A2aMemoryProperties properties, Supplier<MemoryV2RpcService> memoryRpcProvider,
                                MemoryWorkingSetCache cache, ObjectMapper mapper) {
        this.properties = java.util.Objects.requireNonNull(properties);
        this.memoryRpcProvider = java.util.Objects.requireNonNull(memoryRpcProvider);
        this.cache = java.util.Objects.requireNonNull(cache);
        this.mapper = java.util.Objects.requireNonNull(mapper);
        properties.validate();
        this.summaries = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), task -> {
            Thread thread = new Thread(task, "a2a-memory-summary");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public A2aMemoryAgentHook create(String caller, String domain, String agentId, ChatModel summaryModel) {
        return new A2aMemoryAgentHook(caller, domain, agentId, memoryRpcProvider, cache, properties,
                summaryModel, mapper, summaries);
    }

    @Override public void close() { summaries.shutdownNow(); }
}
