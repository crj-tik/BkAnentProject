package com.bkanent.agent.memory;

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

    void upsertUserPreference(UserPreferenceRecord record);

    List<UserPreferenceRecord> getUserPreferences(String userId, String category);

    void decayUserPreferences(String userId, String excludePreferenceKey);

    void upsertSystemConstraint(SystemConstraintRecord record);

    List<SystemConstraintRecord> getSystemConstraints(String category);

    List<SystemConstraintRecord> searchSystemConstraints(String tags);

    void saveSessionMemorySnapshot(SessionMemorySnapshotRequest request);

    Optional<SessionMemoryResponse> getLatestSessionMemorySnapshot(String sessionId);

    WorkflowHistoryView getWorkflowHistory(String taskId);
}
