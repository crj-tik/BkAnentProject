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

/** Creates fixed-identity hooks for the domain's actual ReactAgent. */
public final class A2aMemoryHookFactory implements AutoCloseable {
    private final A2aMemoryProperties properties;
    private final ReferenceConfig<MemoryV2RpcService> memoryReference;
    private final MemoryWorkingSetCache cache;
    private final ObjectMapper mapper;
    private final ExecutorService summaries;

    public A2aMemoryHookFactory(A2aMemoryProperties properties, ReferenceConfig<MemoryV2RpcService> memoryReference,
                                MemoryWorkingSetCache cache, ObjectMapper mapper) {
        this.properties = properties;
        this.memoryReference = memoryReference;
        this.cache = cache;
        this.mapper = mapper;
        properties.validate();
        this.summaries = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), task -> {
            Thread thread = new Thread(task, "a2a-memory-summary");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public A2aMemoryAgentHook create(String caller, String domain, String agentId, ChatModel summaryModel) {
        return new A2aMemoryAgentHook(caller, domain, agentId, memoryReference::get, cache, properties,
                summaryModel, mapper, summaries);
    }

    @Override public void close() { summaries.shutdownNow(); }
}
