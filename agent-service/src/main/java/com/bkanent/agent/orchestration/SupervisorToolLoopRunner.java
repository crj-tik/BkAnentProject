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
    public boolean hasRun(String runId) { return snapshot(runId) != null; }

    public SupervisorTaskResponse execute(SupervisorTaskRequest request) {
        if (request == null || !StringUtils.hasText(request.userMessage()) || !StringUtils.hasText(request.userId()))
            throw new IllegalArgumentException("userMessage and authenticated user required");
        if (StringUtils.hasText(request.continueRunId())) return continueRun(request);
        String runId = text(request.requestId(), UUID.randomUUID().toString());
        String sessionId = text(request.sessionId(), UUID.randomUUID().toString());
        String traceId = text(request.traceId(), runId);
        var normalized = new SupervisorTaskRequest(sessionId, request.userId(), runId, traceId, request.userMessage(),
                request.context(), request.channel(), request.stream(), request.skill(), null, request.allowMcp());
        if (!hasRun(runId) && !properties.isAccepting()) throw new IllegalStateException("SUPERVISOR_ACCEPTANCE_PAUSED");
        store.register(runId, normalized);
        return leased(runId, token -> {
            StateSnapshot existing = snapshot(runId);
            if (existing != null) {
                var state = factory.stateOf(existing.state());
                if (!state.userId.equals(request.userId())) throw new IllegalStateException("CONTINUATION_OWNER_MISMATCH");
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
        return leased(runId, token -> {
            StateSnapshot existing = snapshot(runId);
            if (existing == null) throw new IllegalArgumentException("CONTINUATION_NOT_FOUND");
            ToolLoopState state = factory.stateOf(existing.state());
            validateContinuation(state, request);
            String id = "input-" + store.hash(store.json(Map.of("requestId", request.requestId())));
            var previous = store.find(runId, id);
            if (previous != null) store.prepare(runId, id, "control:continue_input", store.json(Map.of("message", request.userMessage())));
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
            var invocation = store.prepare(runId, id, "control:continue_input", store.json(Map.of("message", request.userMessage())));
            // Lease ownership proves an abandoned internal input claim can be recovered.
            // No external side effect precedes the checkpoint recording this input identity.
            if (!store.claim(runId, id) && !"EXECUTING".equals(invocation.status()))
                throw new IllegalStateException("CONTINUATION_ALREADY_PROCESSING");
            state.messages.add(StoredModelMessage.user(request.userMessage()));
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
        store.assertOwner(runId, userId);
        var existing = snapshot(runId);
        return existing == null ? null : factory.responseOf(factory.stateOf(existing.state()));
    }

    public SupervisorTaskResponse cancel(String runId, String userId) {
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

    private SupervisorTaskResponse leased(String runId, Function<String, SupervisorTaskResponse> operation) {
        String token = UUID.randomUUID().toString();
        if (!store.lease(runId, token, LEASE_MS)) throw new IllegalStateException("run already being processed");
        Future<?> heartbeat = renewals.scheduleAtFixedRate(() -> store.renew(runId, token, LEASE_MS), LEASE_MS / 3, LEASE_MS / 3, TimeUnit.MILLISECONDS);
        try { return operation.apply(token); }
        finally { heartbeat.cancel(false); store.release(runId, token); }
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
