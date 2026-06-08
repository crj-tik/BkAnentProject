package com.bkanent.memory.controller;

import com.bkanent.common.agent.ArtifactCreateRequest;
import com.bkanent.common.agent.ArtifactQueryResponse;
import com.bkanent.common.agent.AgentHandoffPacket;
import com.bkanent.common.agent.HandoffRelationQueryResponse;
import com.bkanent.common.agent.SessionMemoryResponse;
import com.bkanent.common.agent.SessionMemorySnapshotRequest;
import com.bkanent.common.agent.SessionMemoryUpsertRequest;
import com.bkanent.common.agent.SystemConstraintRecord;
import com.bkanent.common.agent.UserPreferenceRecord;
import com.bkanent.common.agent.WorkflowHistoryView;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.memory.service.HandoffRelationMemoryService;
import com.bkanent.memory.service.SessionMemorySnapshotService;
import com.bkanent.memory.service.SessionSharedMemoryService;
import com.bkanent.memory.service.SystemConstraintMemoryService;
import com.bkanent.memory.service.TaskArtifactMemoryService;
import com.bkanent.memory.service.UserPreferenceMemoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/memory")
public class MemoryController {

    private final SessionSharedMemoryService sessionSharedMemoryService;
    private final TaskArtifactMemoryService taskArtifactMemoryService;
    private final HandoffRelationMemoryService handoffRelationMemoryService;
    private final UserPreferenceMemoryService userPreferenceMemoryService;
    private final SystemConstraintMemoryService systemConstraintMemoryService;
    private final SessionMemorySnapshotService sessionMemorySnapshotService;

    public MemoryController(SessionSharedMemoryService sessionSharedMemoryService,
                            TaskArtifactMemoryService taskArtifactMemoryService,
                            HandoffRelationMemoryService handoffRelationMemoryService,
                            UserPreferenceMemoryService userPreferenceMemoryService,
                            SystemConstraintMemoryService systemConstraintMemoryService,
                            SessionMemorySnapshotService sessionMemorySnapshotService) {
        this.sessionSharedMemoryService = sessionSharedMemoryService;
        this.taskArtifactMemoryService = taskArtifactMemoryService;
        this.handoffRelationMemoryService = handoffRelationMemoryService;
        this.userPreferenceMemoryService = userPreferenceMemoryService;
        this.systemConstraintMemoryService = systemConstraintMemoryService;
        this.sessionMemorySnapshotService = sessionMemorySnapshotService;
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

    // --- User Preference Memory ---

    @PostMapping("/user-preferences/upsert")
    public ApiResponse<Void> upsertUserPreference(@RequestBody UserPreferenceRecord record) {
        userPreferenceMemoryService.upsert(record);
        return ApiResponse.ok(null);
    }

    @GetMapping("/user-preferences/{userId}")
    public ApiResponse<List<UserPreferenceRecord>> getUserPreferences(
            @PathVariable String userId,
            @RequestParam(required = false) String category) {
        return ApiResponse.ok(userPreferenceMemoryService.findByUserId(userId, category));
    }

    @PostMapping("/user-preferences/{userId}/decay")
    public ApiResponse<Void> decayUserPreferences(
            @PathVariable String userId,
            @RequestParam(required = false) String excludePreferenceKey) {
        userPreferenceMemoryService.decayConfidence(userId, excludePreferenceKey);
        return ApiResponse.ok(null);
    }

    // --- System Constraint Memory ---

    @PostMapping("/system-constraints/upsert")
    public ApiResponse<Void> upsertSystemConstraint(@RequestBody SystemConstraintRecord record) {
        systemConstraintMemoryService.upsert(record);
        return ApiResponse.ok(null);
    }

    @GetMapping("/system-constraints")
    public ApiResponse<List<SystemConstraintRecord>> getSystemConstraints(
            @RequestParam(required = false) String category) {
        return ApiResponse.ok(systemConstraintMemoryService.findByCategory(category));
    }

    @GetMapping("/system-constraints/search")
    public ApiResponse<List<SystemConstraintRecord>> searchSystemConstraints(
            @RequestParam(required = false) String tags) {
        return ApiResponse.ok(systemConstraintMemoryService.searchByTags(tags));
    }

    // --- Session Memory Snapshot ---

    @PostMapping("/sessions/snapshot")
    public ApiResponse<Void> saveSessionMemorySnapshot(@RequestBody SessionMemorySnapshotRequest request) {
        sessionMemorySnapshotService.save(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/sessions/snapshot/{sessionId}")
    public ApiResponse<SessionMemoryResponse> getLatestSessionMemorySnapshot(@PathVariable String sessionId) {
        return ApiResponse.ok(sessionMemorySnapshotService.getLatest(sessionId).orElse(null));
    }

    // --- Workflow History ---

    @GetMapping("/workflow/{taskId}/history")
    public ApiResponse<WorkflowHistoryView> getWorkflowHistory(@PathVariable String taskId) {
        List<HandoffRelationQueryResponse> handoffs = handoffRelationMemoryService.listByTaskId(taskId);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < handoffs.size(); i++) {
            var h = handoffs.get(i);
            Map<String, Object> step = new HashMap<>();
            step.put("stepNumber", i + 1);
            step.put("agentId", h.packet().toAgent());
            step.put("reason", h.packet().reason());
            step.put("fromAgent", h.packet().fromAgent());
            step.put("timestamp", h.createdAt());
            steps.add(step);
        }
        List<Map<String, Object>> artifactSummaries = new ArrayList<>();
        for (var h : handoffs) {
            if (h.packet().artifactIds() != null) {
                for (String aid : h.packet().artifactIds()) {
                    String taskIdFromPacket = h.packet().taskId();
                    ArtifactQueryResponse artifact = taskArtifactMemoryService.getByArtifactId(aid, taskIdFromPacket, null);
                    if (artifact != null) {
                        Map<String, Object> summary = new HashMap<>();
                        summary.put("artifactId", aid);
                        summary.put("artifactType", artifact.meta().artifactType());
                        summary.put("agentId", artifact.meta().agentId());
                        summary.put("createdAt", artifact.meta().createdAt());
                        artifactSummaries.add(summary);
                    }
                }
            }
        }
        return ApiResponse.ok(new WorkflowHistoryView(steps, artifactSummaries));
    }
}
