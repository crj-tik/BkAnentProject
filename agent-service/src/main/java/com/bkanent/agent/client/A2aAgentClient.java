package com.bkanent.agent.client;

import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.A2aAsyncTaskCreateResponse;
import com.bkanent.common.agent.A2aAsyncTaskStatusResponse;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;

import java.util.function.Consumer;

/**
 * A2aAgentClient A2A 调用客户端。
 */
public interface A2aAgentClient {

    AgentTaskInvokeResponse invoke(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request);

    default boolean supportsStreaming(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        return false;
    }

    default AgentTaskInvokeResponse stream(RegisteredAgentDescriptor descriptor,
                                           AgentTaskInvokeRequest request,
                                           Consumer<ChildAgentStreamEvent> eventConsumer) {
        throw new UnsupportedOperationException("streaming is not supported by the child agent client");
    }

    A2aAsyncTaskCreateResponse submitAsync(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request);

    A2aAsyncTaskStatusResponse queryAsyncStatus(RegisteredAgentDescriptor descriptor, String asyncTaskId);
}
