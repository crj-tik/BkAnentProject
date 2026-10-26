package com.bkanent.agent.orchestration;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.bkanent.agent.graph.official.ApprovalResumeClaimStore;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.common.agent.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static com.bkanent.agent.graph.official.OfficialSupervisorGraphKeys.LATEST_APPROVAL_DECISION;

/** API normalization and durable lease ownership; all model and call decisions remain in Graph. */
@Component
public class SupervisorToolLoopRunner {
    private static final long LEASE_MS = 120_000;
    private final SupervisorToolLoopGraph factory;
    private final CompiledGraph graph;
    private final OrchestrationStore store;
    private final SupervisorOrchestrationProperties properties;
    private final ApprovalResumeClaimStore claims;
    private com.bkanent.agent.authorization.AgentExecutionBindingCoordinator executionBinding;
    @org.springframework.beans.factory.annotation.Autowired
    public void setExecutionBinding(com.bkanent.agent.authorization.AgentExecutionBindingCoordinator coordinator) { executionBinding = coordinator; }
    private com.bkanent.agent.security.AgentRagCredentialLeaseStore ragCredentials;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRagCredentials(com.bkanent.agent.security.AgentRagCredentialLeaseStore credentials) { ragCredentials = credentials; }
    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "supervisor-lease"); thread.setDaemon(true); return thread;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public SupervisorToolLoopRunner(SupervisorToolLoopGraph factory, OrchestrationStore store,
                                    SupervisorOrchestrationProperties properties, ApprovalResumeClaimStore claims) throws Exception {
        this(factory, factory.create(), store, properties, claims);
    }
    public SupervisorToolLoopRunner(SupervisorToolLoopGraph factory, CompiledGraph graph, OrchestrationStore store,
                                    SupervisorOrchestrationProperties properties, ApprovalResumeClaimStore claims) {
        this.factory = factory; this.graph = graph; this.store = store; this.properties = properties; this.claims = claims;
    }
    private com.bkanent.agent.config.AgentChatProperties chatProperties = new com.bkanent.agent.config.AgentChatProperties();
    @org.springframework.beans.factory.annotation.Autowired
    public void setChatProperties(com.bkanent.agent.config.AgentChatProperties value) { chatProperties = value; }

    public void assertChatIdentity(com.bkanent.agent.model.chat.AgentChatRequest request, String sessionId) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (request.continueRunId() == null || request.collectionName() != null || request.topK() != null) {
            context.put("spaceId", com.bkanent.agent.rag.AgentRagCompatibility.space(request.collectionName(), "supervisor.knowledge"));
            context.put("topK", com.bkanent.agent.rag.AgentRagCompatibility.topK(request.topK(), chatProperties.getDefaultTopK()));
        }
        assertRequestIdentity(new SupervisorTaskRequest(sessionId, request.userId(), request.requestId(), null,
                request.message(), context, "chat", false, request.skill(), request.continueRunId(), request.allowMcp()));
    }

    public boolean hasRun(String runId) { return snapshot(runId) != null; }

    public String sessionIdForChat(String continueRunId, String requestId, String sessionId, String actorId) {
        if (!StringUtils.hasText(continueRunId)) {
            String originalSessionId = StringUtils.hasText(requestId) ? store.existingSession(requestId, actorId) : null;
            if (originalSessionId == null) return text(sessionId, UUID.randomUUID().toString());
            if (StringUtils.hasText(sessionId) && !originalSessionId.equals(sessionId)) throw new IllegalStateException("RUN_REQUEST_ID_CONFLICT");
            return originalSessionId;
        }
        store.assertOwner(continueRunId, actorId);
        String originalSessionId = store.originalRequest(continueRunId).sessionId();
        if (StringUtils.hasText(sessionId) && !originalSessionId.equals(sessionId))
            throw new IllegalArgumentException("CONTINUATION_SESSION_MISMATCH");
        return originalSessionId;
    }

    /** Read-only admission check so conflicts cannot claim a new session or consume rate-limit budget. */
    public void assertRequestIdentity(SupervisorTaskRequest request) {
        if (StringUtils.hasText(request.continueRunId())) {
            store.assertOwner(request.continueRunId(), request.userId());
            var existing = snapshot(request.continueRunId());
            if (existing == null) throw new IllegalArgumentException("CONTINUATION_NOT_FOUND");
            validateContinuation(factory.stateOf(existing.state()), request);
        } else if (StringUtils.hasText(request.requestId()) && store.existingSession(request.requestId(), request.userId()) != null) {
            store.assertOriginalRequest(request.requestId(), request);
        }
    }

    public SupervisorTaskResponse execute(SupervisorTaskRequest request) {
        if (request == null || !StringUtils.hasText(request.userMessage()) || !StringUtils.hasText(request.userId()))
            throw new IllegalArgumentException("userMessage and authenticated user required");
        com.bkanent.agent.security.AgentRagContextPolicy.normalize(request.context());
        if (StringUtils.hasText(request.continueRunId())) return continueRun(request);
        String runId = text(request.requestId(), UUID.randomUUID().toString());
        String persistedSessionId = store.existingSession(runId, request.userId());
        String sessionId = text(request.sessionId(), text(persistedSessionId, UUID.randomUUID().toString()));
        String traceId = text(request.traceId(), runId);
        var normalized = new SupervisorTaskRequest(sessionId, request.userId(), runId, traceId, request.userMessage(),
                com.bkanent.agent.security.AgentRagContextPolicy.normalize(request.context()), request.channel(), request.stream(), request.skill(), null, request.allowMcp(), request.matterIntent());
        if (!hasRun(runId) && !properties.isAccepting()) throw new IllegalStateException("SUPERVISOR_ACCEPTANCE_PAUSED");
        assertRequestIdentity(normalized);
        bindExecution(normalized);
        store.register(runId, normalized);
        if (ragCredentials != null) ragCredentials.capture(runId, normalized.userId());
        return leased(runId, token -> {
            StateSnapshot existing = snapshot(runId);
            if (existing != null) {
                var state = factory.stateOf(existing.state());
                if (!state.userId.equals(request.userId())) throw new IllegalStateException("CONTINUATION_OWNER_MISMATCH");
                store.assertSameRequest(state.request, normalized);
                if ("FAILED".equals(state.status) && state.errorCode != null && state.errorCode.startsWith("MEMORY_")
                        && state.memoryResumeNode != null) {
                    validateContinuation(state, request);
                    if (!state.request.userMessage().equals(request.userMessage()) || !java.util.Objects.equals(state.request.matterIntent(), request.matterIntent())) throw new IllegalArgumentException("CONTINUATION_INPUT_CHANGED");
                    if (!Set.of("PrepareContext", "Model", "LoadSkill", "MemoryRecall", "WaitInput", "ApprovalGate", "Observe", "ExecuteTool", "Complete")
                            .contains(state.memoryResumeNode)) throw new IllegalStateException("MEMORY_RECOVERY_INVALID");
                    state.leaseToken = token; state.status = "RUNNING"; state.errorCode = null;
                    state.finalAnswer = state.memoryPendingAnswer == null ? "" : state.memoryPendingAnswer;
                    String nextNode = state.memoryResumeNode; state.memoryResumeNode = null; state.memoryPendingAnswer = null;
                    Map<String,Object> restored = new LinkedHashMap<>(existing.state().data());
                    restored.putAll(factory.updates(state, "MemoryRecovery"));
                    var checkpoint = com.alibaba.cloud.ai.graph.checkpoint.Checkpoint.builder().state(restored).nodeId("MemoryRecovery").nextNodeId(nextNode).build();
                    var saver = graph.compileConfig.checkpointSaver().orElseThrow();
                    try { return invoke(Map.of(), saver.put(config(runId), checkpoint).withResume()); }
                    catch (Exception exception) { throw new IllegalStateException("memory requires checkpoint recovery", exception); }
                }
                if (!"RUNNING".equals(state.status)) return factory.responseOf(state);
                state.leaseToken = token;
                try { return invoke(Map.of(), graph.updateState(existing.config(), factory.updates(state, "Recovery")).withResume()); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }
            ToolLoopState state = new ToolLoopState();
            state.request = normalized; state.runId = runId; state.sessionId = sessionId; state.traceId = traceId;
            state.userId = normalized.userId(); state.mode = OrchestrationMode.from(normalized.skill()).name();
            state.leaseToken = token;
            state.allowMcp = normalized.allowMcp() == null || normalized.allowMcp();
            state.maxRounds = Math.max(1, properties.getMaxRounds()); state.maxToolCalls = Math.max(1, properties.getMaxToolCalls());
            return invoke(factory.updates(state, "PrepareContext"), config(runId));
        });
    }

    private SupervisorTaskResponse continueRun(SupervisorTaskRequest request) {
        String runId = request.continueRunId();
        if (!StringUtils.hasText(request.requestId())) throw new IllegalArgumentException("continuation requestId required");
        store.assertOwner(runId, request.userId());
        assertRequestIdentity(request);
        bindExecution(request);
        if (ragCredentials != null) ragCredentials.capture(runId, request.userId());
        return leased(runId, token -> {
            StateSnapshot existing = snapshot(runId);
            if (existing == null) throw new IllegalArgumentException("CONTINUATION_NOT_FOUND");
            ToolLoopState state = factory.stateOf(existing.state());
            validateContinuation(state, request);
            String id = "input-" + store.hash(store.json(Map.of("requestId", request.requestId())));
            var previous = store.find(runId, id);
            Map<String,Object> input = new LinkedHashMap<>(); input.put("message",request.userMessage());
            if(request.matterIntent()!=null) input.put("matterIntent",request.matterIntent());
            if (previous != null) store.prepare(runId, id, "control:continue_input", store.json(input));
            if (previous != null && "COMPLETED".equals(previous.status()))
                return store.read(previous.result(), SupervisorTaskResponse.class);
            state.leaseToken = token;
            if (previous != null && id.equals(state.lastInputId)) {
                try {
                    var response = "RUNNING".equals(state.status)
                            ? invoke(Map.of(), graph.updateState(existing.config(), factory.updates(state, "Recovery")).withResume())
                            : factory.responseOf(state);
                    store.complete(runId, id, store.json(response));
                    return response;
                } catch (Exception exception) { throw new IllegalStateException("continuation requires checkpoint recovery", exception); }
            }
            if (!"WAITING_USER_INPUT".equals(state.status)) throw new IllegalStateException("CONTINUATION_STATE_INVALID");
            var invocation = store.prepare(runId, id, "control:continue_input", store.json(input));
            // Lease ownership proves an abandoned internal input claim can be recovered.
            // No external side effect precedes the checkpoint recording this input identity.
            if (!store.claim(runId, id) && !"EXECUTING".equals(invocation.status()))
                throw new IllegalStateException("CONTINUATION_ALREADY_PROCESSING");
            state.messages.add(StoredModelMessage.user(request.userMessage(),state.memoryMatterId,request.matterIntent()));
            state.lastInputId = id;
            state.status = "RUNNING"; state.question = null; state.missingFields = List.of(); state.finalAnswer = "";
            try {
                RunnableConfig resumed = graph.updateState(existing.config(), factory.updates(state, "PauseInput")).withResume();
                SupervisorTaskResponse response = invoke(Map.of(), resumed);
                store.complete(runId, id, store.json(response));
                return response;
            } catch (Exception exception) { throw new IllegalStateException("continuation requires checkpoint recovery", exception); }
        });
    }

    private void validateContinuation(ToolLoopState state, SupervisorTaskRequest request) {
        if (!state.userId.equals(request.userId())) throw new IllegalStateException("CONTINUATION_OWNER_MISMATCH");
        if (StringUtils.hasText(request.sessionId()) && !state.sessionId.equals(request.sessionId()))
            throw new IllegalArgumentException("CONTINUATION_SESSION_MISMATCH");
        if (request.allowMcp() != null && request.allowMcp() != state.allowMcp)
            throw new IllegalArgumentException("CONTINUATION_POLICY_CHANGED");
        if (request.skill() != null) {
            var selection = request.skill(); var snapshot = state.skillSnapshot;
            if (snapshot == null || !snapshot.definition().name().equals(selection.name())
                    || selection.version() != null && !snapshot.definition().version().equals(selection.version())
                    || selection.contentHash() != null && !snapshot.contentHash().equals(selection.contentHash())
                    || selection.owner() != null && !snapshot.definition().owner().equals(selection.owner()))
                throw new IllegalArgumentException("SKILL_SELECTION_LOCKED");
        }
        Map<String, Object> supplied = new LinkedHashMap<>(request.context() == null ? Map.of() : request.context());
        supplied.remove("userId");
        Map<String, Object> original = new LinkedHashMap<>(state.request.context() == null ? Map.of() : state.request.context());
        original.remove("userId");
        if (!supplied.isEmpty() && !supplied.equals(original)) throw new IllegalArgumentException("CONTINUATION_POLICY_CHANGED");
    }

    public SupervisorTaskResponse resume(ApprovalCallbackRequest request) {
        if (request.status() == null || request.status() == ApprovalStatus.PENDING) throw new IllegalArgumentException("approval decision required");
        store.assertOwner(request.taskId(), request.reviewerId());
        assertApprovalIdentity(request);
        if (executionBinding != null) {
            var fixedState = factory.stateOf(snapshot(request.taskId()).state());
            Map<String, Object> fixedDecision = new LinkedHashMap<>();
            fixedDecision.put("decision", request);
            fixedDecision.put("originalRequest", fixedState.request);
            fixedDecision.put("pendingApproval", fixedState.pendingApproval);
            fixedDecision.put("skillSnapshot", fixedState.skillSnapshot);
            fixedDecision.put("allowMcp", fixedState.allowMcp);
            executionBinding.bindApproval(request.reviewerId(), request.taskId(),
                    store.originalRequest(request.taskId()).sessionId(), fixedDecision);
        }
        else requireNoUnboundEntry();
        if (ragCredentials != null) ragCredentials.capture(request.taskId(), request.reviewerId());
        return leased(request.taskId(), token -> {
            StateSnapshot existing = snapshot(request.taskId());
            if (existing == null) throw new IllegalArgumentException("run not found");
            ToolLoopState state = factory.stateOf(existing.state());
            store.assertOwner(request.taskId(), request.reviewerId());
            state.leaseToken = token;
            if (StringUtils.hasText(request.sessionId()) && !state.sessionId.equals(request.sessionId())) throw new IllegalArgumentException("approval session mismatch");
            ApprovalDecision previousDecision = existing.state().value(LATEST_APPROVAL_DECISION)
                    .map(value -> store.read(store.json(value), ApprovalDecision.class)).orElse(null);
            boolean pendingMatches = state.pendingApproval != null && request.approvalId().equals(state.pendingApproval.approvalId());
            boolean replayMatches = previousDecision != null && request.approvalId().equals(previousDecision.approvalId());
            if (!pendingMatches && !replayMatches) throw new IllegalArgumentException("approval does not belong to run");
            if (replayMatches) {
                if (previousDecision.status() != request.status()) throw new IllegalArgumentException("approval decision conflict");
                if (state.pendingApproval != null && !pendingMatches) {
                    var response = factory.responseOf(state);
                    claims.completeRecovered(request, response);
                    return response;
                }
                try {
                    var response = AsyncRunStatus.terminal(state.status) || "WAITING_USER_INPUT".equals(state.status)
                            ? factory.responseOf(state)
                            : invoke(Map.of(), graph.updateState(existing.config(), factory.updates(state, "Recovery")).withResume());
                    claims.completeRecovered(request, response);
                    return response;
                } catch (Exception exception) { throw new IllegalStateException("approval requires checkpoint recovery", exception); }
            }
            var claim = claims.claim(request);
            if (!claim.acquired()) return claim.replayedResponse();
            if (!"WAITING_USER_APPROVAL".equals(state.status) || state.pendingApproval == null
                    || !request.approvalId().equals(state.pendingApproval.approvalId())
                    || request.approvalVersion() != null && !request.approvalVersion().equals(state.pendingApproval.subjectVersion())) {
                var exception = new IllegalArgumentException("approval does not match current call"); claims.fail(request.approvalId(), exception); throw exception;
            }
            try {
                var decision = new ApprovalDecision(request.approvalId(), request.status(), request.reviewerId(), request.feedback(), LocalDateTime.now(), request.traceId());
                Map<String, Object> update = new LinkedHashMap<>(factory.updates(state, "PauseApproval"));
                update.put(LATEST_APPROVAL_DECISION, decision);
                var resumed = graph.updateState(existing.config(), update).withResume();
                var response = invoke(Map.of(), resumed); claims.complete(request.approvalId(), response); return response;
            } catch (Exception exception) { claims.fail(request.approvalId(), exception); throw new IllegalStateException(exception); }
        });
    }

    public SupervisorTaskResponse current(String runId, String userId) {
        if (ragCredentials != null) ragCredentials.credentials(userId);
        store.assertOwner(runId, userId);
        var existing = snapshot(runId);
        if (existing == null) return null;
        var state = factory.stateOf(existing.state());
        factory.assertMemoryOutput(state);
        return factory.responseOf(state);
    }

    public SupervisorTaskResponse cancel(String runId, String userId) {
        if (ragCredentials != null) { ragCredentials.credentials(userId); ragCredentials.capture(runId, userId); }
        store.cancel(runId, userId);
        factory.cancelRemoteTasks(runId, userId);
        try {
            return leased(runId, token -> {
                var existing = snapshot(runId);
                if (existing == null) throw new IllegalArgumentException("RUN_NOT_FOUND");
                var state = factory.stateOf(existing.state());
                if (!AsyncRunStatus.terminal(state.status)) {
                    state.status = "CANCELED"; state.finalAnswer = "执行已取消。"; state.errorCode = "RUN_CANCELLED";
                    state.pendingApproval = null;
                    try { graph.updateState(existing.config(), factory.updates(state, "Cancelled")); }
                    catch (Exception exception) { throw new IllegalStateException(exception); }
                }
                return factory.responseOf(state);
            });
        } catch (IllegalStateException exception) {
            if (!"run already being processed".equals(exception.getMessage())) throw exception;
            // An active owner observes the durable cancellation before its next model/call boundary.
            var response = current(runId, userId);
            return new SupervisorTaskResponse(response.sessionId(), response.taskId(), "CANCEL_REQUESTED", response.finalAnswer(),
                    response.artifactIds(), response.traceId(), response.selectedAgentId(), response.governanceMetadata());
        }
    }

    public SupervisorTaskResponse reconcile(String runId, String userId) {
        store.assertOwner(runId, userId);
        if (ragCredentials != null) ragCredentials.capture(runId, userId);
        return leased(runId, token -> {
            var existing = snapshot(runId);
            if (existing == null) throw new IllegalArgumentException("RUN_NOT_FOUND");
            var state = factory.stateOf(existing.state());
            if (!"OUTCOME_UNKNOWN".equals(state.errorCode)) return factory.responseOf(state);
            state.leaseToken = token;
            if (!factory.reconcileResults(state)) return factory.responseOf(state);
            try {
                // A terminal checkpoint points at END. Restart at Model with the complete persisted state,
                // bypassing PrepareContext and preserving original messages, snapshots and call identities.
                var update = factory.updates(state, "Observe");
                var saver = graph.compileConfig.checkpointSaver().orElseThrow();
                Map<String, Object> restored = new LinkedHashMap<>(existing.state().data()); restored.putAll(update);
                var checkpoint = com.alibaba.cloud.ai.graph.checkpoint.Checkpoint.builder().state(restored).nodeId("Observe").nextNodeId("Model").build();
                var resumed = saver.put(config(runId), checkpoint).withResume();
                return invoke(Map.of(), resumed);
            }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        });
    }

    private void assertApprovalIdentity(ApprovalCallbackRequest request) {
        var existing = snapshot(request.taskId());
        if (existing == null) throw new IllegalArgumentException("run not found");
        var state = factory.stateOf(existing.state());
        if (!request.reviewerId().equals(state.userId)
                || StringUtils.hasText(request.sessionId()) && !state.sessionId.equals(request.sessionId()))
            throw new IllegalArgumentException("approval session mismatch");
        ApprovalDecision previous = existing.state().value(LATEST_APPROVAL_DECISION)
                .map(value -> store.read(store.json(value), ApprovalDecision.class)).orElse(null);
        boolean pending = state.pendingApproval != null && request.approvalId().equals(state.pendingApproval.approvalId());
        boolean replay = previous != null && request.approvalId().equals(previous.approvalId());
        if (!pending && !replay || replay && previous.status() != request.status()
                || pending && request.approvalVersion() != null
                && !request.approvalVersion().equals(state.pendingApproval.subjectVersion())
                || !replay && !"WAITING_USER_APPROVAL".equals(state.status))
            throw new IllegalArgumentException("approval does not match current call");
    }

    private void bindExecution(SupervisorTaskRequest request) {
        if (executionBinding != null) executionBinding.bind(request);
        else requireNoUnboundEntry();
    }

    private void requireNoUnboundEntry() {
        var entry = com.bkanent.agent.authorization.AgentAuthorizationScope.current();
        if (entry != null) entry.requireRunAssociation();
    }

    private SupervisorTaskResponse leased(String runId, Function<String, SupervisorTaskResponse> operation) {
        String token = UUID.randomUUID().toString();
        if (!store.lease(runId, token, LEASE_MS)) throw new IllegalStateException("run already being processed");
        Future<?> heartbeat = renewals.scheduleAtFixedRate(() -> store.renew(runId, token, LEASE_MS), LEASE_MS / 3, LEASE_MS / 3, TimeUnit.MILLISECONDS);
        try {
        String owner = store.originalRequest(runId).userId();
        try (var ignored = ragCredentials == null ? (AutoCloseable) () -> { } : ragCredentials.openLease(runId, owner)) {
            if (ragCredentials != null) ragCredentials.credentials(owner);
            var response = operation.apply(token);
            if (ragCredentials != null) ragCredentials.credentials(owner);
            var checked = snapshot(runId);
            if (checked != null) factory.assertMemoryOutput(factory.stateOf(checked.state()));
            factory.releaseMemory(runId);
            if (ragCredentials != null && AsyncRunStatus.terminal(response.status())) ragCredentials.discard(runId);
            return response;
        }
        catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("credential scope cleanup failed"); }
        } finally { factory.releaseMemory(runId); heartbeat.cancel(false); store.release(runId, token); }
    }
    private SupervisorTaskResponse invoke(Map<String, Object> input, RunnableConfig config) {
        return factory.responseOf(factory.stateOf(graph.invoke(input, config)
                .orElseThrow(() -> new IllegalStateException("tool loop returned empty state"))));
    }
    private StateSnapshot snapshot(String runId) { return graph.lastStateOf(config(runId)).orElse(null); }
    private RunnableConfig config(String runId) { return RunnableConfig.builder().threadId(runId).build(); }
    private String text(String value, String fallback) { return StringUtils.hasText(value) ? value : fallback; }
    @PreDestroy public void close() { renewals.shutdownNow(); }
}
