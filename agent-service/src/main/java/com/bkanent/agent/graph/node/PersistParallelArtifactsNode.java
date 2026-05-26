package com.bkanent.agent.graph.node;

import com.bkanent.agent.workflow.TaskArtifactStore;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.AgentTaskInvokeResponse;
import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
public class PersistParallelArtifactsNode {

    private final TaskArtifactStore taskArtifactStore;
    private final SessionStreamService sessionStreamService;

    public PersistParallelArtifactsNode(TaskArtifactStore taskArtifactStore,
                                        SessionStreamService sessionStreamService) {
        this.taskArtifactStore = taskArtifactStore;
        this.sessionStreamService = sessionStreamService;
    }

    public List<String> persist(String taskId,
                                String sessionId,
                                String userId,
                                String traceId,
                                List<String> parallelDomains,
                                AgentTaskInvokeResponse mergedResponse) {
        List<String> artifactIds = new ArrayList<>(mergedResponse.artifactIds() == null ? List.of() : mergedResponse.artifactIds());
        String artifactId = taskArtifactStore.save(
                taskId,
                sessionId,
                "parallel-supervisor",
                "parallel_result",
                1,
                mergedResponse.structuredOutput(),
                Map.of("parallelDomains", parallelDomains, "summary", mergedResponse.summary()),
                traceId
        );
        sessionStreamService.publish(new SessionStreamEvent(
                sessionId,
                taskId,
                "parallel-supervisor",
                "artifact.created",
                "Parallel artifact persisted",
                Map.of(
                        "artifactId", artifactId,
                        "artifactType", "parallel_result",
                        "parallelDomains", parallelDomains,
                        "userId", userId == null ? "" : userId
                ),
                traceId,
                System.currentTimeMillis()
        ));
        artifactIds.add(artifactId);
        return List.copyOf(new LinkedHashSet<>(artifactIds));
    }
}
