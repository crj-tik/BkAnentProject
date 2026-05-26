package com.bkanent.agent.memory;

import com.bkanent.common.agent.ArtifactCreateRequest;
import com.bkanent.common.agent.ArtifactQueryResponse;
import com.bkanent.common.agent.AgentHandoffPacket;
import com.bkanent.common.agent.HandoffRelationQueryResponse;
import com.bkanent.common.agent.SessionMemoryResponse;
import com.bkanent.common.agent.SessionMemoryUpsertRequest;

import java.util.List;
import java.util.Optional;

public interface MemoryStoreClient {

    void upsertSessionMemory(SessionMemoryUpsertRequest request);

    Optional<SessionMemoryResponse> getSessionMemory(String sessionId);

    ArtifactQueryResponse createArtifact(ArtifactCreateRequest request);

    List<ArtifactQueryResponse> listArtifactsByTask(String taskId, String sessionId);

    ArtifactQueryResponse getArtifactById(String artifactId);

    HandoffRelationQueryResponse createHandoffRelation(AgentHandoffPacket packet);

    List<HandoffRelationQueryResponse> listHandoffsByTask(String taskId);
}
