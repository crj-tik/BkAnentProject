package com.bkanent.agent.graph.node;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.workflow.SupervisorWorkflowState;
import com.bkanent.common.agent.SessionMemoryUpsertRequest;
import org.springframework.stereotype.Component;

@Component
public class PersistSessionNode {

    private final MemoryStoreClient memoryStoreClient;

    public PersistSessionNode(MemoryStoreClient memoryStoreClient) {
        this.memoryStoreClient = memoryStoreClient;
    }

    public void persist(SupervisorWorkflowState state, String summary) {
        memoryStoreClient.upsertSessionMemory(new SessionMemoryUpsertRequest(
                state.sessionId(),
                state.userId(),
                state.sharedContext(),
                summary,
                state.traceId()
        ));
    }
}
