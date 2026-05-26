package com.bkanent.agent.client;

import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.registry.AgentRuntimeType;
import com.bkanent.common.agent.A2aAsyncTaskCreateResponse;
import com.bkanent.common.agent.A2aAsyncTaskStatusResponse;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class DelegatingA2aAgentClient implements A2aAgentClient {

    private final HttpA2aAgentClient httpA2aAgentClient;
    private final OfficialA2aAgentClient officialA2aAgentClient;

    public DelegatingA2aAgentClient(HttpA2aAgentClient httpA2aAgentClient,
                                    OfficialA2aAgentClient officialA2aAgentClient) {
        this.httpA2aAgentClient = httpA2aAgentClient;
        this.officialA2aAgentClient = officialA2aAgentClient;
    }

    @Override
    public AgentTaskInvokeResponse invoke(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        if (shouldUseOfficial(descriptor, request)) {
            return officialA2aAgentClient.invoke(descriptor, request);
        }
        return httpA2aAgentClient.invoke(descriptor, request);
    }

    @Override
    public A2aAsyncTaskCreateResponse submitAsync(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        if (shouldUseOfficial(descriptor, request)) {
            return officialA2aAgentClient.submitAsync(descriptor, request);
        }
        return httpA2aAgentClient.submitAsync(descriptor, request);
    }

    @Override
    public A2aAsyncTaskStatusResponse queryAsyncStatus(RegisteredAgentDescriptor descriptor, String asyncTaskId) {
        if (shouldUseOfficial(descriptor)) {
            return officialA2aAgentClient.queryAsyncStatus(descriptor, asyncTaskId);
        }
        return httpA2aAgentClient.queryAsyncStatus(descriptor, asyncTaskId);
    }

    private boolean shouldUseOfficial(RegisteredAgentDescriptor descriptor) {
        return descriptor != null
                && descriptor.runtimeType() == AgentRuntimeType.ALIBABA_A2A;
    }

    private boolean shouldUseOfficial(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        return shouldUseOfficial(descriptor);
    }
}
