package com.bkanent.agent.workflow;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.common.agent.ArtifactCreateRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Primary
@Component
public class RemoteTaskArtifactStore implements TaskArtifactStore {

    private final MemoryStoreClient memoryStoreClient;

    public RemoteTaskArtifactStore(MemoryStoreClient memoryStoreClient) {
        this.memoryStoreClient = memoryStoreClient;
    }

    @Override
    public String save(String taskId,
                       String sessionId,
                       String agentId,
                       String artifactType,
                       Integer versionNo,
                       Object content,
                       Map<String, Object> metadata,
                       String traceId) {
        return memoryStoreClient.createArtifact(new ArtifactCreateRequest(
                taskId,
                sessionId,
                agentId,
                artifactType,
                versionNo,
                content,
                metadata,
                traceId
        )).meta().artifactId();
    }

    @Override
    public Optional<String> findBySourceArtifactId(String taskId,
                                                   String sessionId,
                                                   String agentId,
                                                   String sourceArtifactId) {
        if (sourceArtifactId == null || sourceArtifactId.isBlank()) {
            return Optional.empty();
        }
        return memoryStoreClient.listArtifactsByTask(taskId, sessionId).stream()
                .filter(response -> response != null && response.meta() != null)
                .filter(response -> agentId == null || agentId.equals(response.meta().agentId()))
                .filter(response -> sourceArtifactId.equals(
                        response.meta().metadata() == null ? null : response.meta().metadata().get("sourceArtifactId")))
                .map(response -> response.meta().artifactId())
                .filter(id -> id != null && !id.isBlank())
                .findFirst();
    }
}
