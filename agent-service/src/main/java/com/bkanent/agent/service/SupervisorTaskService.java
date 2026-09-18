package com.bkanent.agent.service;

import com.bkanent.agent.graph.official.OfficialSupervisorGraphFacade;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.common.agent.AgentCard;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synchronous Supervisor entry facade. Workflow transitions belong to the
 * official Graph; this service only validates the request and decorates the
 * response with governance metadata.
 */
@Service
public class SupervisorTaskService {

    private final AgentRegistry agentRegistry;
    private final OfficialSupervisorGraphFacade supervisorGraphFacade;
    private final SupervisorGovernanceService supervisorGovernanceService;

    public SupervisorTaskService(AgentRegistry agentRegistry,
                                 OfficialSupervisorGraphFacade supervisorGraphFacade,
                                 SupervisorGovernanceService supervisorGovernanceService) {
        this.agentRegistry = agentRegistry;
        this.supervisorGraphFacade = supervisorGraphFacade;
        this.supervisorGovernanceService = supervisorGovernanceService;
    }

    public SupervisorTaskResponse submitTask(SupervisorTaskRequest request) {
        String message = request.userMessage() == null ? "" : request.userMessage().trim();
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
        SupervisorTaskResponse response = supervisorGraphFacade.execute(request);
        return withGovernanceMetadata(response,
                supervisorGovernanceService.extractGovernanceMetadata(request));
    }

    public List<AgentCard> listAgents() {
        return agentRegistry.listCards();
    }

    private SupervisorTaskResponse withGovernanceMetadata(SupervisorTaskResponse response,
                                                          Map<String, Object> metadata) {
        Map<String, Object> merged = new LinkedHashMap<>(
                response.governanceMetadata() == null ? Map.of() : response.governanceMetadata());
        if (metadata != null) {
            merged.putAll(metadata);
        }
        return new SupervisorTaskResponse(
                response.sessionId(),
                response.taskId(),
                response.status(),
                response.finalAnswer(),
                response.artifactIds(),
                response.traceId(),
                response.selectedAgentId(),
                Map.copyOf(merged)
        );
    }
}
