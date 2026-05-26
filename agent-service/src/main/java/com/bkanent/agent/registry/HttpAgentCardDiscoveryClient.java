package com.bkanent.agent.registry;

import com.bkanent.common.agent.AgentCard;
import com.bkanent.common.model.ApiResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
public class HttpAgentCardDiscoveryClient implements AgentCardDiscoveryClient {

    private static final ParameterizedTypeReference<ApiResponse<AgentCard>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient.Builder restClientBuilder;

    public HttpAgentCardDiscoveryClient(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public Optional<AgentCard> fetchAgentCard(String baseUrl, String cardPath) {
        try {
            ApiResponse<AgentCard> response = restClientBuilder.build()
                    .get()
                    .uri(baseUrl + cardPath)
                    .retrieve()
                    .body(RESPONSE_TYPE);
            if (response == null || !response.success() || response.data() == null) {
                return Optional.empty();
            }
            return Optional.of(response.data());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }
}
