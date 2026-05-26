package com.bkanent.agent.client;

import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.common.agent.A2aAsyncTaskCreateResponse;
import com.bkanent.common.agent.A2aAsyncTaskStatusResponse;
import com.bkanent.common.agent.A2aErrorCodes;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.model.ApiResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HttpA2aAgentClient HTTP 版 A2A 客户端。
 */
@Component
public class HttpA2aAgentClient implements A2aAgentClient {

    private static final ParameterizedTypeReference<ApiResponse<AgentTaskInvokeResponse>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<A2aAsyncTaskCreateResponse>> ASYNC_CREATE_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<A2aAsyncTaskStatusResponse>> ASYNC_STATUS_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient.Builder restClientBuilder;

    public HttpA2aAgentClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public AgentTaskInvokeResponse invoke(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        try {
            ApiResponse<AgentTaskInvokeResponse> response = restClientBuilder.build()
                    .post()
                    .uri(descriptor.baseUrl() + descriptor.a2aPath())
                    .body(request)
                    .retrieve()
                    .body(RESPONSE_TYPE);
            if (response == null) {
                throw new IllegalStateException(A2aErrorCodes.A2A_AGENT_UNAVAILABLE + ": empty response from " + descriptor.agentId());
            }
            if (!response.success()) {
                throw new IllegalStateException(response.code() + ": " + response.message());
            }
            if (response.data() == null) {
                throw new IllegalStateException(A2aErrorCodes.A2A_RESULT_INVALID + ": empty result from " + descriptor.agentId());
            }
            return response.data();
        } catch (RestClientException exception) {
            throw new IllegalStateException(A2aErrorCodes.A2A_AGENT_UNAVAILABLE + ": " + descriptor.agentId(), exception);
        }
    }

    @Override
    public A2aAsyncTaskCreateResponse submitAsync(RegisteredAgentDescriptor descriptor, AgentTaskInvokeRequest request) {
        try {
            ApiResponse<A2aAsyncTaskCreateResponse> response = restClientBuilder.build()
                    .post()
                    .uri(descriptor.baseUrl() + descriptor.a2aTaskCreatePath())
                    .body(request)
                    .retrieve()
                    .body(ASYNC_CREATE_RESPONSE_TYPE);
            if (response == null || !response.success() || response.data() == null) {
                throw new IllegalStateException(A2aErrorCodes.A2A_RESULT_INVALID + ": async create failed for " + descriptor.agentId());
            }
            return response.data();
        } catch (RestClientException exception) {
            throw new IllegalStateException(A2aErrorCodes.A2A_AGENT_UNAVAILABLE + ": " + descriptor.agentId(), exception);
        }
    }

    @Override
    public A2aAsyncTaskStatusResponse queryAsyncStatus(RegisteredAgentDescriptor descriptor, String asyncTaskId) {
        try {
            ApiResponse<A2aAsyncTaskStatusResponse> response = restClientBuilder.build()
                    .get()
                    .uri(descriptor.baseUrl() + descriptor.a2aTaskStatusPath() + "?asyncTaskId=" + asyncTaskId)
                    .retrieve()
                    .body(ASYNC_STATUS_RESPONSE_TYPE);
            if (response == null || !response.success() || response.data() == null) {
                throw new IllegalStateException(A2aErrorCodes.A2A_RESULT_INVALID + ": async status failed for " + descriptor.agentId());
            }
            return response.data();
        } catch (RestClientException exception) {
            throw new IllegalStateException(A2aErrorCodes.A2A_AGENT_UNAVAILABLE + ": " + descriptor.agentId(), exception);
        }
    }

}
