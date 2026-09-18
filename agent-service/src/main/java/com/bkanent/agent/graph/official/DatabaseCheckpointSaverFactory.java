package com.bkanent.agent.graph.official;

import com.bkanent.agent.mapper.AgentWorkflowCheckpointMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Creates an isolated saver for each compiled graph.
 *
 * <p>Graph instances share a task id, but their checkpoint timelines are not
 * interchangeable. The saver therefore keeps the same database adapter while
 * tagging persisted envelopes with the graph name and using a separate
 * in-memory cache per compiled graph.</p>
 */
@Component
public class DatabaseCheckpointSaverFactory {

    private final AgentWorkflowCheckpointMapper checkpointMapper;
    private final ObjectMapper objectMapper;

    public DatabaseCheckpointSaverFactory(AgentWorkflowCheckpointMapper checkpointMapper,
                                          ObjectMapper objectMapper) {
        this.checkpointMapper = checkpointMapper;
        this.objectMapper = objectMapper;
    }

    public DatabaseCheckpointSaver create(String graphName) {
        return new DatabaseCheckpointSaver(checkpointMapper, objectMapper, graphName);
    }
}
