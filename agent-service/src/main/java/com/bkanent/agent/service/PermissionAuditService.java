package com.bkanent.agent.service;

import com.bkanent.agent.config.DistributedAgentProperties;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class PermissionAuditService {

    private final SessionStreamService sessionStreamService;
    private final DistributedAgentProperties distributedAgentProperties;
    private final AgentMetricsService agentMetricsService;

    public PermissionAuditService(SessionStreamService sessionStreamService,
                                  DistributedAgentProperties distributedAgentProperties,
                                  AgentMetricsService agentMetricsService) {
        this.sessionStreamService = sessionStreamService;
        this.distributedAgentProperties = distributedAgentProperties;
        this.agentMetricsService = agentMetricsService;
    }

    public void publishDenied(String sessionId,
                              String taskId,
                              String action,
                              Map<String, Object> metadata,
                              String traceId) {
        agentMetricsService.recordPermissionDenied(action);
        sessionStreamService.publish(new SessionStreamEvent(
                sessionId,
                taskId,
                distributedAgentProperties.getSupervisorAgentId(),
                "security.permission_denied",
                "Permission denied for " + action,
                metadata == null ? Map.of("action", action, "stage", "security") : withAction(metadata, action),
                traceId == null ? UUID.randomUUID().toString() : traceId,
                System.currentTimeMillis()
        ));
    }

    private Map<String, Object> withAction(Map<String, Object> metadata, String action) {
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(metadata);
        merged.putIfAbsent("action", action);
        merged.putIfAbsent("stage", "security");
        return Map.copyOf(merged);
    }
}
