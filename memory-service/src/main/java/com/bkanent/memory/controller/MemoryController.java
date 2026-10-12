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
import com.bkanent.memory.service.MemoryRagSecurity;
import com.bkanent.memory.service.MemoryRagSearchFacade;
import com.bkanent.memory.service.MemoryRagBackfillService;
import com.bkanent.common.rag.*;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final MemoryRagSecurity ragSecurity;
    private final MemoryRagSearchFacade ragSearch;
    private final MemoryRagBackfillService ragBackfill;

    public MemoryController(SessionSharedMemoryService sessionSharedMemoryService,
                            TaskArtifactMemoryService taskArtifactMemoryService,
                            HandoffRelationMemoryService handoffRelationMemoryService,
                            UserPreferenceMemoryService userPreferenceMemoryService,
                            SystemConstraintMemoryService systemConstraintMemoryService,
                            SessionMemorySnapshotService sessionMemorySnapshotService,
                            MemoryRagSecurity ragSecurity, MemoryRagSearchFacade ragSearch,
                            MemoryRagBackfillService ragBackfill) {
        this.sessionSharedMemoryService = sessionSharedMemoryService;
        this.taskArtifactMemoryService = taskArtifactMemoryService;
        this.handoffRelationMemoryService = handoffRelationMemoryService;
        this.userPreferenceMemoryService = userPreferenceMemoryService;
        this.systemConstraintMemoryService = systemConstraintMemoryService;
        this.sessionMemorySnapshotService = sessionMemorySnapshotService;
        this.ragSecurity = ragSecurity;
        this.ragSearch = ragSearch;
        this.ragBackfill = ragBackfill;
    }

    @PostMapping("/sessions/upsert")
    public ApiResponse<Void> upsertSessionMemory(@RequestBody SessionMemoryUpsertRequest request) {
        sessionSharedMemoryService.upsert(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/sessions")
    public ApiResponse<SessionMemoryResponse> getSessionMemory(@RequestParam("sessionId") String sessionId) {
        return ApiResponse.ok(sessionSharedMemoryService.find(sessionId).orElse(null));
    }

    @PostMapping("/artifacts")
    public ApiResponse<ArtifactQueryResponse> createArtifact(@RequestBody ArtifactCreateRequest request) {
        return ApiResponse.ok(taskArtifactMemoryService.create(request));
    }

    @GetMapping("/artifacts/by-task")
    public ApiResponse<List<ArtifactQueryResponse>> listArtifactsByTask(@RequestParam("taskId") String taskId,
                                                                        @RequestParam("sessionId") String sessionId) {
        return ApiResponse.ok(taskArtifactMemoryService.listByTaskId(taskId, sessionId));
    }

    @GetMapping("/artifacts/by-id")
    public ApiResponse<ArtifactQueryResponse> getArtifactById(@RequestParam("artifactId") String artifactId,
                                                              @RequestParam("taskId") String taskId,
                                                              @RequestParam(value = "sessionId", required = false) String sessionId) {
        return ApiResponse.ok(taskArtifactMemoryService.getByArtifactId(artifactId, taskId, sessionId));
    }

    @PostMapping("/handoffs")
    public ApiResponse<HandoffRelationQueryResponse> createHandoffRelation(@RequestBody AgentHandoffPacket packet) {
        return ApiResponse.ok(handoffRelationMemoryService.create(packet));
    }

    @GetMapping("/handoffs/by-task")
    public ApiResponse<List<HandoffRelationQueryResponse>> listHandoffsByTask(@RequestParam("taskId") String taskId) {
        return ApiResponse.ok(handoffRelationMemoryService.listByTaskId(taskId));
    }

    @GetMapping("/handoffs/by-session")
    public ApiResponse<List<HandoffRelationQueryResponse>> listHandoffsBySession(@RequestParam("sessionId") String sessionId) {
        return ApiResponse.ok(handoffRelationMemoryService.listBySessionId(sessionId));
    }

    // --- User Preference Memory ---

    @PostMapping("/user-preferences/upsert")
    public ApiResponse<Void> upsertUserPreference(@RequestBody UserPreferenceRecord record,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ragSecurity.requireOwner(ragSecurity.authenticate(credentials(authorization)), record.userId());
        userPreferenceMemoryService.upsert(record);
        return ApiResponse.ok(null);
    }

    @GetMapping("/user-preferences/{userId}")
    public ApiResponse<List<UserPreferenceRecord>> getUserPreferences(
            @PathVariable("userId") String userId,
            @RequestParam(value = "category", required = false) String category,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ragSecurity.requireOwner(ragSecurity.authenticate(credentials(authorization)), userId);
        return ApiResponse.ok(userPreferenceMemoryService.findByUserId(userId, category));
    }

    @PostMapping("/user-preferences/{userId}/decay")
    public ApiResponse<Void> decayUserPreferences(
            @PathVariable("userId") String userId,
            @RequestParam(value = "excludePreferenceKey", required = false) String excludePreferenceKey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ragSecurity.requireOwner(ragSecurity.authenticate(credentials(authorization)), userId);
        userPreferenceMemoryService.decayConfidence(userId, excludePreferenceKey);
        return ApiResponse.ok(null);
    }

    // --- System Constraint Memory ---

    @PostMapping("/system-constraints/upsert")
    public ApiResponse<Void> upsertSystemConstraint(@RequestBody SystemConstraintRecord record,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ragSecurity.requireAdmin(ragSecurity.authenticate(credentials(authorization)));
        systemConstraintMemoryService.upsert(record);
        return ApiResponse.ok(null);
    }

    @GetMapping("/system-constraints")
    public ApiResponse<List<SystemConstraintRecord>> getSystemConstraints(
            @RequestParam(value = "category", required = false) String category,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ragSecurity.requireConstraintRead(ragSecurity.authenticate(credentials(authorization)));
        return ApiResponse.ok(systemConstraintMemoryService.findByCategory(category));
    }

    @GetMapping("/system-constraints/search")
    public ApiResponse<List<SystemConstraintRecord>> searchSystemConstraints(
            @RequestParam(value = "tags", required = false) String tags,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ragSecurity.requireConstraintRead(ragSecurity.authenticate(credentials(authorization)));
        return ApiResponse.ok(systemConstraintMemoryService.searchByTags(tags));
    }

    @PostMapping("/rag/search")
    public ApiResponse<MemoryRagSearchResponse> searchMemory(@RequestBody MemoryRagSearchRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        var credentials = credentials(authorization);
        return ApiResponse.ok(ragSearch.search(request, ragSecurity.authenticate(credentials), credentials));
    }

    @PostMapping("/rag/backfill")
    public ApiResponse<Integer> backfillMemory(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.ok(ragBackfill.backfill(credentials(authorization)));
    }

    private static RagCredentialContext credentials(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank()) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
        return new RagCredentialContext(authorization.substring(7));
    }

    // --- Session Memory Snapshot ---

    @PostMapping("/sessions/snapshot")
    public ApiResponse<Void> saveSessionMemorySnapshot(@RequestBody SessionMemorySnapshotRequest request) {
        sessionMemorySnapshotService.save(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/sessions/snapshot/{sessionId}")
    public ApiResponse<SessionMemoryResponse> getLatestSessionMemorySnapshot(@PathVariable("sessionId") String sessionId) {
        return ApiResponse.ok(sessionMemorySnapshotService.getLatest(sessionId).orElse(null));
    }

    // --- Workflow History ---

    @GetMapping("/workflow/{taskId}/history")
    public ApiResponse<WorkflowHistoryView> getWorkflowHistory(@PathVariable("taskId") String taskId) {
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
