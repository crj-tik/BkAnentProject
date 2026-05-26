package com.bkanent.agent.graph.node;

import com.bkanent.agent.graph.SupervisorGraphNode;
import com.bkanent.agent.graph.SupervisorGraphState;
import com.bkanent.agent.memory.MemoryStoreClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LoadSessionNode implements SupervisorGraphNode {

    private final MemoryStoreClient memoryStoreClient;

    public LoadSessionNode(MemoryStoreClient memoryStoreClient) {
        this.memoryStoreClient = memoryStoreClient;
    }

    @Override
    public SupervisorGraphState apply(SupervisorGraphState state) {
        Map<String, Object> sharedContext = new LinkedHashMap<>();
        memoryStoreClient.getSessionMemory(state.sessionId())
                .map(memory -> memory.memory() == null ? Map.<String, Object>of() : memory.memory())
                .ifPresent(sharedContext::putAll);
        if (state.sharedContext() != null) {
            sharedContext.putAll(state.sharedContext());
        }
        return state.withSharedContext(Map.copyOf(sharedContext));
    }
}
