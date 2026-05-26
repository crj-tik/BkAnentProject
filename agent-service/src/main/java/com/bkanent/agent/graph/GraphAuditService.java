package com.bkanent.agent.graph;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class GraphAuditService {

    private final SessionStreamService sessionStreamService;
    private final DistributedAgentProperties distributedAgentProperties;
    private final com.bkanent.agent.service.AgentMetricsService agentMetricsService;

    public GraphAuditService(SessionStreamService sessionStreamService,
                             DistributedAgentProperties distributedAgentProperties,
                             com.bkanent.agent.service.AgentMetricsService agentMetricsService) {
        this.sessionStreamService = sessionStreamService;
        this.distributedAgentProperties = distributedAgentProperties;
        this.agentMetricsService = agentMetricsService;
    }

    public long markStart(String sessionId,
                          String taskId,
                          String traceId,
                          String subgraphName,
                          Map<String, Object> metadata) {
        long startedAt = System.currentTimeMillis();
        publish(sessionId, taskId, traceId, "graph.subgraph.started", subgraphName,
                merge(metadata, subgraphName, "RUNNING", startedAt, 0L, null));
        return startedAt;
    }

    public void markCompleted(String sessionId,
                              String taskId,
                              String traceId,
                              String subgraphName,
                              long startedAt,
                              Map<String, Object> metadata) {
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        agentMetricsService.recordGraphSubgraph(subgraphName, "COMPLETED", durationMs);
        publish(sessionId, taskId, traceId, "graph.subgraph.completed", subgraphName,
                merge(metadata, subgraphName, "COMPLETED", startedAt, durationMs, null));
    }

    public void markFailed(String sessionId,
                           String taskId,
                           String traceId,
                           String subgraphName,
                           long startedAt,
                           Exception exception,
                           Map<String, Object> metadata) {
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        agentMetricsService.recordGraphSubgraph(subgraphName, "FAILED", durationMs);
        publish(sessionId, taskId, traceId, "graph.subgraph.failed", subgraphName,
                merge(metadata, subgraphName, "FAILED", startedAt, durationMs, exception == null ? null : exception.getMessage()));
    }

    private void publish(String sessionId,
                         String taskId,
                         String traceId,
                         String eventType,
                         String subgraphName,
                         Map<String, Object> metadata) {
        sessionStreamService.publish(new SessionStreamEvent(
                sessionId,
                taskId,
                distributedAgentProperties.getSupervisorAgentId(),
                eventType,
                subgraphName,
                metadata,
                traceId,
                System.currentTimeMillis()
        ));
    }

    private Map<String, Object> merge(Map<String, Object> metadata,
                                      String subgraphName,
                                      String status,
                                      long startedAt,
                                      long durationMs,
                                      String errorMessage) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        merged.put("stage", "graph");
        merged.put("subgraph", subgraphName);
        merged.put("status", status);
        merged.put("startedAt", startedAt);
        merged.put("durationMs", durationMs);
        if (errorMessage != null && !errorMessage.isBlank()) {
            merged.put("errorMessage", errorMessage.length() > 250 ? errorMessage.substring(0, 250) : errorMessage);
        }
        return Map.copyOf(merged);
    }
}
