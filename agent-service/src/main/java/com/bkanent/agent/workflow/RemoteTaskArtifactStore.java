package com.bkanent.agent.workflow;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.common.agent.ArtifactCreateRequest;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;

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
}
