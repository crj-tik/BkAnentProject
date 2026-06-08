package com.bkanent.agent.memory;

import com.bkanent.agent.config.MemoryServiceProperties;
import com.bkanent.common.agent.ArtifactCreateRequest;
import com.bkanent.common.agent.ArtifactQueryResponse;
import com.bkanent.common.agent.AgentHandoffPacket;
import com.bkanent.common.agent.HandoffRelationQueryResponse;
import com.bkanent.common.agent.SessionMemoryResponse;
import com.bkanent.common.agent.SessionMemorySnapshotRequest;
import com.bkanent.common.agent.SessionMemoryUpsertRequest;
import com.bkanent.common.agent.SystemConstraintRecord;
import com.bkanent.common.agent.UserPreferenceRecord;
import com.bkanent.common.agent.WorkflowHistoryView;
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

    @Override
    public void upsertUserPreference(UserPreferenceRecord record) {
        restClient.post()
                .uri(properties.getUserPreferenceUpsertPath())
                .body(record)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                });
    }

    @Override
    public List<UserPreferenceRecord> getUserPreferences(String userId, String category) {
        ApiResponse<List<UserPreferenceRecord>> response = restClient.get()
                .uri(UriComponentsBuilder.fromPath(properties.getUserPreferenceQueryPath())
                        .queryParam("category", category)
                        .buildAndExpand(userId)
                        .toUri())
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<UserPreferenceRecord>>>() {
                });
        return response == null || response.data() == null ? List.of() : response.data();
    }

    @Override
    public void decayUserPreferences(String userId, String excludePreferenceKey) {
        restClient.post()
                .uri(UriComponentsBuilder.fromPath(properties.getUserPreferenceDecayPath())
                        .queryParam("excludePreferenceKey", excludePreferenceKey)
                        .buildAndExpand(userId)
                        .toUri())
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                });
    }

    @Override
    public void upsertSystemConstraint(SystemConstraintRecord record) {
        restClient.post()
                .uri(properties.getSystemConstraintUpsertPath())
                .body(record)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                });
    }

    @Override
    public List<SystemConstraintRecord> getSystemConstraints(String category) {
        ApiResponse<List<SystemConstraintRecord>> response = restClient.get()
                .uri(UriComponentsBuilder.fromPath(properties.getSystemConstraintQueryPath())
                        .queryParam("category", category)
                        .build()
                        .toUri())
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<SystemConstraintRecord>>>() {
                });
        return response == null || response.data() == null ? List.of() : response.data();
    }

    @Override
    public List<SystemConstraintRecord> searchSystemConstraints(String tags) {
        ApiResponse<List<SystemConstraintRecord>> response = restClient.get()
                .uri(UriComponentsBuilder.fromPath(properties.getSystemConstraintSearchPath())
                        .queryParam("tags", tags)
                        .build()
                        .toUri())
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<SystemConstraintRecord>>>() {
                });
        return response == null || response.data() == null ? List.of() : response.data();
    }

    @Override
    public void saveSessionMemorySnapshot(SessionMemorySnapshotRequest request) {
        restClient.post()
                .uri(properties.getSessionSnapshotPath())
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                });
    }

    @Override
    public Optional<SessionMemoryResponse> getLatestSessionMemorySnapshot(String sessionId) {
        ApiResponse<SessionMemoryResponse> response = restClient.get()
                .uri(properties.getSessionSnapshotQueryPath(), sessionId)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<SessionMemoryResponse>>() {
                });
        return response == null ? Optional.empty() : Optional.ofNullable(response.data());
    }

    @Override
    public WorkflowHistoryView getWorkflowHistory(String taskId) {
        ApiResponse<WorkflowHistoryView> response = restClient.get()
                .uri(properties.getWorkflowHistoryPath(), taskId)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<WorkflowHistoryView>>() {
                });
        if (response == null || response.data() == null) {
            return new WorkflowHistoryView(List.of(), List.of());
        }
        return response.data();
    }
}
