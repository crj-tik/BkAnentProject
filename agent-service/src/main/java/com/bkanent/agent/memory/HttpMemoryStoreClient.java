package com.bkanent.agent.memory;

import com.bkanent.agent.config.MemoryServiceProperties;
import com.bkanent.common.agent.ArtifactCreateRequest;
import com.bkanent.common.agent.ArtifactQueryResponse;
import com.bkanent.common.agent.AgentHandoffPacket;
import com.bkanent.common.agent.HandoffRelationQueryResponse;
import com.bkanent.common.agent.SessionMemoryResponse;
import com.bkanent.common.agent.SessionMemoryUpsertRequest;
import com.bkanent.common.model.ApiResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Optional;

@Component
public class HttpMemoryStoreClient implements MemoryStoreClient {

    private final RestClient restClient;
    private final MemoryServiceProperties properties;

    public HttpMemoryStoreClient(RestClient.Builder restClientBuilder,
                                 MemoryServiceProperties properties) {
        this.restClient = restClientBuilder.baseUrl(properties.getBaseUrl()).build();
        this.properties = properties;
    }

    @Override
    public void upsertSessionMemory(SessionMemoryUpsertRequest request) {
        restClient.post()
                .uri(properties.getSessionUpsertPath())
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                });
    }

    @Override
    public Optional<SessionMemoryResponse> getSessionMemory(String sessionId) {
        ApiResponse<SessionMemoryResponse> response = restClient.get()
                .uri(UriComponentsBuilder.fromPath(properties.getSessionQueryPath())
                        .queryParam("sessionId", sessionId)
                        .build()
                        .toUri())
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<SessionMemoryResponse>>() {
                });
        return response == null ? Optional.empty() : Optional.ofNullable(response.data());
    }

    @Override
    public ArtifactQueryResponse createArtifact(ArtifactCreateRequest request) {
        ApiResponse<ArtifactQueryResponse> response = restClient.post()
                .uri(properties.getArtifactCreatePath())
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<ArtifactQueryResponse>>() {
                });
        if (response == null || response.data() == null) {
            throw new IllegalStateException("Memory service returned empty artifact response");
        }
        return response.data();
    }

    @Override
    public List<ArtifactQueryResponse> listArtifactsByTask(String taskId, String sessionId) {
        ApiResponse<List<ArtifactQueryResponse>> response = restClient.get()
                .uri(UriComponentsBuilder.fromPath(properties.getArtifactQueryByTaskPath())
                        .queryParam("taskId", taskId)
                        .queryParam("sessionId", sessionId)
                        .build()
                        .toUri())
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<ArtifactQueryResponse>>>() {
                });
        return response == null || response.data() == null ? List.of() : response.data();
    }

    @Override
    public ArtifactQueryResponse getArtifactById(String artifactId) {
        ApiResponse<ArtifactQueryResponse> response = restClient.get()
                .uri(UriComponentsBuilder.fromPath(properties.getArtifactQueryByIdPath())
                        .queryParam("artifactId", artifactId)
                        .build()
                        .toUri())
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<ArtifactQueryResponse>>() {
                });
        if (response == null || response.data() == null) {
            throw new IllegalStateException("Memory service returned empty artifact detail response");
        }
        return response.data();
    }

    @Override
    public HandoffRelationQueryResponse createHandoffRelation(AgentHandoffPacket packet) {
        ApiResponse<HandoffRelationQueryResponse> response = restClient.post()
                .uri(properties.getHandoffCreatePath())
                .body(packet)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<HandoffRelationQueryResponse>>() {
                });
        if (response == null || response.data() == null) {
            throw new IllegalStateException("Memory service returned empty handoff relation response");
        }
        return response.data();
    }

    @Override
    public List<HandoffRelationQueryResponse> listHandoffsByTask(String taskId) {
        ApiResponse<List<HandoffRelationQueryResponse>> response = restClient.get()
                .uri(UriComponentsBuilder.fromPath(properties.getHandoffQueryByTaskPath())
                        .queryParam("taskId", taskId)
                        .build()
                        .toUri())
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<HandoffRelationQueryResponse>>>() {
                });
        return response == null || response.data() == null ? List.of() : response.data();
    }
}
