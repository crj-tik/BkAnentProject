package com.bkanent.agent.service;

import com.bkanent.agent.config.AgentChatProperties;
import com.bkanent.agent.model.chat.*;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.orchestration.SupervisorToolLoopRunner;
import com.bkanent.agent.tool.context.AgentToolSessionSnapshot;
import com.bkanent.agent.milvus.core.model.MilvusSearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.*;

/** Ordinary chat uses the same execution owner and preserves its original response fields. */
@Service
public class AgentOrchestratorService {
    private final SupervisorToolLoopRunner runner;
    private final AgentChatProperties properties;
    private final ObjectMapper mapper;
    public AgentOrchestratorService(SupervisorToolLoopRunner runner, AgentChatProperties properties, ObjectMapper mapper) {
        this.runner = runner; this.properties = properties; this.mapper = mapper;
    }

    public AgentChatResponse chat(AgentChatRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (request.continueRunId() == null || request.collectionName() != null || request.topK() != null) {
            context.put("collectionName", request.collectionName() == null ? "agent_knowledge" : request.collectionName());
            context.put("topK", request.topK() == null ? properties.getDefaultTopK() : Math.max(1, request.topK()));
        }
        var response = runner.execute(new SupervisorTaskRequest(request.sessionId(), request.userId(), request.requestId(), null,
                request.message(), context, "chat", false, request.skill(), request.continueRunId(), request.allowMcp()));
        List<AgentToolSessionSnapshot> snapshots = new ArrayList<>();
        if (response.governanceMetadata().get("calls") instanceof List<?> calls) for (Object call : calls) {
            if (call instanceof Map<?, ?> fact && fact.get("toolContext") != null)
                snapshots.add(mapper.convertValue(fact.get("toolContext"), AgentToolSessionSnapshot.class));
        }
        var first = snapshots.stream().filter(AgentToolSessionSnapshot::usedTool).findFirst().orElse(null);
        List<MilvusSearchResult> results = snapshots.stream().flatMap(snapshot -> snapshot.milvusResults().stream()).toList();
        String trace = snapshots.stream().map(AgentToolSessionSnapshot::toolContext).filter(value -> !value.isBlank())
                .reduce((left, right) -> left + System.lineSeparator() + right).orElse("");
        var decision = new AgentToolDecision(!results.isEmpty(), first == null ? null : first.firstToolName(),
                first == null ? null : first.firstToolQuery(), first == null ? null : first.topK(),
                first == null ? "Model decided no retrieval tool call was needed" : "Model invoked tools; results recorded by the shared execution loop");
        return new AgentChatResponse(response.finalAnswer(), properties.getModel(), decision, results, trace,
                response.taskId(), response.status(), response.governanceMetadata());
    }
}
