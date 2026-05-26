package com.bkanent.memory.controller;

import com.bkanent.common.agent.ArtifactCreateRequest;
import com.bkanent.common.agent.ArtifactQueryResponse;
import com.bkanent.common.agent.AgentHandoffPacket;
import com.bkanent.common.agent.HandoffRelationQueryResponse;
import com.bkanent.common.agent.SessionMemoryResponse;
import com.bkanent.common.agent.SessionMemoryUpsertRequest;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.memory.service.HandoffRelationMemoryService;
import com.bkanent.memory.service.SessionSharedMemoryService;
import com.bkanent.memory.service.TaskArtifactMemoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/memory")
public class MemoryController {

    private final SessionSharedMemoryService sessionSharedMemoryService;
    private final TaskArtifactMemoryService taskArtifactMemoryService;
    private final HandoffRelationMemoryService handoffRelationMemoryService;

    public MemoryController(SessionSharedMemoryService sessionSharedMemoryService,
                            TaskArtifactMemoryService taskArtifactMemoryService,
                            HandoffRelationMemoryService handoffRelationMemoryService) {
        this.sessionSharedMemoryService = sessionSharedMemoryService;
        this.taskArtifactMemoryService = taskArtifactMemoryService;
        this.handoffRelationMemoryService = handoffRelationMemoryService;
    }

    @PostMapping("/sessions/upsert")
    public ApiResponse<Void> upsertSessionMemory(@RequestBody SessionMemoryUpsertRequest request) {
        sessionSharedMemoryService.upsert(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/sessions")
    public ApiResponse<SessionMemoryResponse> getSessionMemory(@RequestParam String sessionId) {
        return ApiResponse.ok(sessionSharedMemoryService.find(sessionId).orElse(null));
    }

    @PostMapping("/artifacts")
    public ApiResponse<ArtifactQueryResponse> createArtifact(@RequestBody ArtifactCreateRequest request) {
        return ApiResponse.ok(taskArtifactMemoryService.create(request));
    }

    @GetMapping("/artifacts/by-task")
    public ApiResponse<List<ArtifactQueryResponse>> listArtifactsByTask(@RequestParam String taskId,
                                                                        @RequestParam String sessionId) {
        return ApiResponse.ok(taskArtifactMemoryService.listByTaskId(taskId, sessionId));
    }

    @GetMapping("/artifacts/by-id")
    public ApiResponse<ArtifactQueryResponse> getArtifactById(@RequestParam String artifactId,
                                                              @RequestParam String taskId,
                                                              @RequestParam(required = false) String sessionId) {
        return ApiResponse.ok(taskArtifactMemoryService.getByArtifactId(artifactId, taskId, sessionId));
    }

    @PostMapping("/handoffs")
    public ApiResponse<HandoffRelationQueryResponse> createHandoffRelation(@RequestBody AgentHandoffPacket packet) {
        return ApiResponse.ok(handoffRelationMemoryService.create(packet));
    }

    @GetMapping("/handoffs/by-task")
    public ApiResponse<List<HandoffRelationQueryResponse>> listHandoffsByTask(@RequestParam String taskId) {
        return ApiResponse.ok(handoffRelationMemoryService.listByTaskId(taskId));
    }

    @GetMapping("/handoffs/by-session")
    public ApiResponse<List<HandoffRelationQueryResponse>> listHandoffsBySession(@RequestParam String sessionId) {
        return ApiResponse.ok(handoffRelationMemoryService.listBySessionId(sessionId));
    }
}
