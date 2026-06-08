package com.bkanent.agent.graph.node;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.service.UserPreferenceCollector;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.SessionMemorySnapshotRequest;
import com.bkanent.common.agent.SessionMemoryUpsertRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PersistSessionNode {

    private static final Logger log = LoggerFactory.getLogger(PersistSessionNode.class);

    private final MemoryStoreClient memoryStoreClient;
    private final UserPreferenceCollector userPreferenceCollector;

    public PersistSessionNode(MemoryStoreClient memoryStoreClient,
                              UserPreferenceCollector userPreferenceCollector) {
        this.memoryStoreClient = memoryStoreClient;
        this.userPreferenceCollector = userPreferenceCollector;
    }

    public void persist(SupervisorWorkflowState state, String summary) {
        memoryStoreClient.upsertSessionMemory(new SessionMemoryUpsertRequest(
                state.sessionId(),
                state.userId(),
                state.sharedContext(),
                summary,
                state.traceId()
        ));

        saveVersionedSnapshot(state, summary);

        triggerPreferenceCollection(state, summary);
    }

    private void saveVersionedSnapshot(SupervisorWorkflowState state, String summary) {
        try {
            int version = memoryStoreClient.getLatestSessionMemorySnapshot(state.sessionId())
                    .map(s -> s.version() != null ? s.version() + 1 : 1)
                    .orElse(1);
            memoryStoreClient.saveSessionMemorySnapshot(new SessionMemorySnapshotRequest(
                    state.sessionId(),
                    state.taskId(),
                    version,
                    state.sharedContext() != null ? Map.copyOf(state.sharedContext()) : Map.of(),
                    summary,
                    state.traceId()
            ));
        } catch (Exception e) {
            log.debug("Failed to save session memory snapshot: {}", e.getMessage());
        }
    }

    private void triggerPreferenceCollection(SupervisorWorkflowState state, String summary) {
        String userId = state.userId();
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            userPreferenceCollector.collectAsync(
                    userId,
                    state.sessionId(),
                    state.userMessage(),
                    state.sharedContext(),
                    summary
            );
        } catch (Exception e) {
            log.debug("Failed to trigger user preference collection: {}", e.getMessage());
        }
    }
}
