package com.bkanent.agent.orchestration;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.bkanent.agent.graph.official.DatabaseCheckpointSaverFactory;
import com.bkanent.agent.graph.official.OfficialSupervisorGraphSchema;
import com.bkanent.agent.model.distributed.SupervisorTaskResponse;
import com.bkanent.agent.stream.SessionStreamService;
import com.bkanent.common.agent.*;
import com.bkanent.common.skill.core.SkillRegistry;
import com.bkanent.common.skill.runtime.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static com.bkanent.agent.graph.official.OfficialSupervisorGraphKeys.*;

/** Fixed generic topology. Skills never create nodes, dependencies, or completion predicates. */
@Component
public class SupervisorToolLoopGraph {
    public static final String STATE_KEY = "toolLoopState";
    public static final String RUNNER_VERSION = "llm-tools-v1";
    private final SupervisorCapabilityCatalog catalog;
    private final SupervisorModelTurn model;
    private final SkillRegistry skills;
    private final OrchestrationStore store;
    private final ObjectMapper mapper;
    private final SupervisorOrchestrationProperties properties;
    private final SessionStreamService events;
    private final OfficialSupervisorGraphSchema schema;
    private final DatabaseCheckpointSaverFactory saverFactory;
    private final ExecutorService workers;
    private RemoteTaskReconciler reconciler;

    @org.springframework.beans.factory.annotation.Autowired
    public void setReconciler(RemoteTaskReconciler reconciler) { this.reconciler = reconciler; }

    public SupervisorToolLoopGraph(SupervisorCapabilityCatalog catalog, SupervisorModelTurn model, SkillRegistry skills,
                                   OrchestrationStore store, ObjectMapper mapper, SupervisorOrchestrationProperties properties,
                                   SessionStreamService events, OfficialSupervisorGraphSchema schema,
                                   DatabaseCheckpointSaverFactory saverFactory) {
        this.catalog = catalog; this.model = model; this.skills = skills; this.store = store;
        this.mapper = mapper; this.properties = properties; this.events = events;
        this.schema = schema; this.saverFactory = saverFactory;
        workers = Executors.newFixedThreadPool(Math.max(1, properties.getMaxConcurrency()), runnable -> {
            Thread thread = new Thread(runnable, "supervisor-tool"); thread.setDaemon(true); return thread;
        });
    }

    public CompiledGraph create() throws Exception { return create(saverFactory.create(RUNNER_VERSION)); }
    public CompiledGraph create(BaseCheckpointSaver saver) throws Exception {
        var strategies = new HashMap<>(schema.keyStrategyFactory().apply());
        strategies.put(STATE_KEY, new ReplaceStrategy());
        StateGraph graph = new StateGraph(RUNNER_VERSION, () -> strategies);
        graph.addNode("PrepareContext", AsyncNodeAction.node_async(this::prepare));
        graph.addNode("Model", AsyncNodeAction.node_async(this::model));
        graph.addNode("Dispatch", AsyncNodeAction.node_async(this::dispatch));
        graph.addNode("LoadSkill", AsyncNodeAction.node_async(this::loadSkill));
        graph.addNode("GuardCall", AsyncNodeAction.node_async(this::guard));
        graph.addNode("ApprovalGate", AsyncNodeAction.node_async(this::approval));
        graph.addNode("PauseApproval", AsyncNodeAction.node_async(state -> updates(stateOf(state), "PauseApproval")));
        graph.addNode("WaitInput", AsyncNodeAction.node_async(this::waitInput));
        graph.addNode("PauseInput", AsyncNodeAction.node_async(state -> updates(stateOf(state), "PauseInput")));
        graph.addNode("ExecuteTool", AsyncNodeAction.node_async(this::execute));
        graph.addNode("Observe", AsyncNodeAction.node_async(this::observe));
        graph.addNode("Complete", AsyncNodeAction.node_async(this::complete));
        graph.addEdge(StateGraph.START, "PrepareContext");
        graph.addEdge("PrepareContext", "Model");
        graph.addEdge("Model", "Dispatch");
        graph.addConditionalEdges("Dispatch", AsyncEdgeAction.edge_async(state -> stateOf(state).route),
                Map.of("skill", "LoadSkill", "input", "WaitInput", "calls", "GuardCall", "observe", "Observe", "complete", "Complete"));
        graph.addEdge("LoadSkill", "Observe");
        graph.addConditionalEdges("GuardCall", AsyncEdgeAction.edge_async(state -> stateOf(state).route),
                Map.of("valid", "ApprovalGate", "observe", "Observe", "complete", "Complete"));
        graph.addConditionalEdges("ApprovalGate", AsyncEdgeAction.edge_async(state -> stateOf(state).route),
                Map.of("pending", "PauseApproval", "execute", "ExecuteTool"));
        graph.addEdge("PauseApproval", "ExecuteTool");
        graph.addConditionalEdges("WaitInput", AsyncEdgeAction.edge_async(state -> "WAITING_USER_INPUT".equals(stateOf(state).status) ? "wait" : "observe"),
                Map.of("wait", "PauseInput", "observe", "Observe"));
        graph.addEdge("PauseInput", "Model");
        graph.addConditionalEdges("ExecuteTool", AsyncEdgeAction.edge_async(state -> "RUNNING".equals(stateOf(state).status) ? "observe" : "complete"),
                Map.of("observe", "Observe", "complete", "Complete"));
        graph.addEdge("Observe", "Model");
        graph.addEdge("Complete", StateGraph.END);
        return graph.compile(CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build())
                .recursionLimit(Math.max(100, properties.getMaxRounds() * 10 + 20))
                .interruptAfter("PauseApproval", "PauseInput").build());
    }

    public ToolLoopState stateOf(OverAllState state) {
        return mapper.convertValue(state.value(STATE_KEY).orElseThrow(), ToolLoopState.class);
    }

    private Map<String, Object> prepare(OverAllState raw) {
        ToolLoopState state = stateOf(raw);
        try {
            var available = catalog.snapshot(state.userId, state.allowMcp);
            if (state.request.skill() != null) {
                state.skillSnapshot = new SkillExecutionResolver(skills).resolve(state.request.skill(), "supervisor", names(available), true);
                store.snapshot(state.runId, state.skillSnapshot);
            }
            state.messages.add(StoredModelMessage.user(state.request.userMessage()));
        } catch (SkillExecutionException exception) { fail(state, exception.code(), exception.getMessage()); }
        return updates(state, "PrepareContext");
    }

    private Map<String, Object> model(OverAllState raw) {
        ToolLoopState state = stateOf(raw);
        if (!"RUNNING".equals(state.status)) return updates(state, "Model");
        var available = available(state);
        state.visibleCapabilityIds = new ArrayList<>(available.keySet());
        state.visibleCapabilityVersions = new LinkedHashMap<>();
        available.forEach((id, capability) -> state.visibleCapabilityVersions.put(id, capability.version()));
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(prompt(state)));
        state.messages.stream().map(StoredModelMessage::message).forEach(messages::add);
        List<ToolCallback> definitions = new ArrayList<>(available.values().stream().map(SupervisorCapability::callback).toList());
        definitions.add(control("skill", "加载本次任务适用的发布技能；须单独调用，等待加载结果后再调用业务工具。",
                "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"minLength\":1},\"task\":{\"type\":\"string\",\"minLength\":1},\"version\":{\"type\":\"string\"}},\"required\":[\"name\",\"task\"],\"additionalProperties\":false}"));
        definitions.add(control("request_input", "需要用户补充信息时单独调用，明确问题和缺失信息。",
                "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\",\"minLength\":1},\"missingFields\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"question\"],\"additionalProperties\":false}"));
        Exception last = null;
        for (int attempt = 0; attempt <= Math.max(0, properties.getModelRetries()); attempt++) {
            if (++state.rounds > state.maxRounds) { fail(state, "BUDGET_EXHAUSTED", "模型轮次额度耗尽"); break; }
            try {
                AssistantMessage output = model.call(messages, definitions);
                state.messages.add(StoredModelMessage.assistant(output));
                state.pendingCalls = new ArrayList<>(output.getToolCalls());
                state.results.clear();
                if (!output.hasToolCalls()) state.finalAnswer = output.getText() == null ? "" : output.getText();
                last = null; break;
            } catch (Exception exception) { last = exception; }
        }
        if (last != null && "RUNNING".equals(state.status)) fail(state, "MODEL_UNAVAILABLE", "模型服务调用失败");
        return updates(state, "Model");
    }

    private Map<String, Object> dispatch(OverAllState raw) {
        ToolLoopState state = stateOf(raw);
        if (!"RUNNING".equals(state.status) || state.pendingCalls.isEmpty()) { state.route = "complete"; return updates(state, "Dispatch"); }
        int controls = (int) state.pendingCalls.stream().filter(call -> controlName(call.name())).count();
        boolean invalidIds = state.pendingCalls.stream().anyMatch(call -> call.id() == null || call.id().isBlank() || call.id().length() > 128)
                || state.pendingCalls.stream().map(AssistantMessage.ToolCall::id).distinct().count() != state.pendingCalls.size();
        if (state.toolCalls + state.pendingCalls.size() > state.maxToolCalls) {
            rejectBatch(state, "BUDGET_EXHAUSTED"); fail(state, "BUDGET_EXHAUSTED", "工具调用额度耗尽"); state.route = "complete";
        } else if (invalidIds || (controls > 0 && (controls != 1 || state.pendingCalls.size() != 1))) {
            state.toolCalls += state.pendingCalls.size();
            rejectBatch(state, invalidIds ? "TOOL_CALL_ID_INVALID" : "CONTROL_BATCH_REJECTED"); state.route = "observe";
        } else {
            state.toolCalls += state.pendingCalls.size();
            state.route = controls == 0 ? "calls" : "skill".equals(state.pendingCalls.get(0).name()) ? "skill" : "input";
        }
        return updates(state, "Dispatch");
    }

    private Map<String, Object> loadSkill(OverAllState raw) {
        ToolLoopState state = stateOf(raw);
        var call = state.pendingCalls.get(0);
        try {
            validateArguments(call.arguments(), controlSchema("skill"));
            var invocation = store.prepare(state.runId, call.id(), "control:skill", call.arguments());
            if ("COMPLETED".equals(invocation.status())) {
                Map<?, ?> saved = store.read(invocation.result(), Map.class);
                if (saved.get("snapshot") != null) state.skillSnapshot = mapper.convertValue(saved.get("snapshot"), SkillExecutionSnapshot.class);
                state.results.add(response(call, String.valueOf(saved.get("result"))));
            } else if (store.claim(state.runId, call.id())) {
                Map<?, ?> arguments = mapper.readValue(call.arguments(), Map.class);
                var available = catalog.snapshot(state.userId, state.allowMcp);
                Map<String, String> identities = names(available);
                if (state.skillSnapshot != null) state.skillSnapshot.capabilityIds().forEach(id -> identities.putIfAbsent(id, SupervisorCapabilityCatalog.alias(id)));
                var context = SkillExecutionContext.restored(skills, "supervisor", state.request.userMessage(), identities,
                        state.skillSnapshot, "EXPLICIT_SKILL".equals(state.mode));
                var loaded = context.activate(new SkillSelection((String) arguments.get("name"), (String) arguments.get("version")));
                store.snapshot(state.runId, loaded);
                String result = "技能已加载: " + loaded.definition().name() + " / " + loaded.definition().version()
                        + "\n" + loaded.definition().systemPrompt() + "\n原始请求: " + state.request.userMessage()
                        + "\n本次技能任务: " + arguments.get("task") + "\n能力范围: " + loaded.capabilityIds();
                store.complete(state.runId, call.id(), store.json(Map.of("snapshot", loaded, "result", result)));
                state.skillSnapshot = loaded; state.results.add(response(call, result));
            } else throw new IllegalStateException("OUTCOME_UNKNOWN: skill load needs reconciliation");
        } catch (Exception exception) {
            String failure = error(exception);
            var invocation = store.find(state.runId, call.id());
            if (invocation != null && "EXECUTING".equals(invocation.status())) {
                store.complete(state.runId, call.id(), store.json(Map.of("result", failure)));
            }
            state.results.add(response(call, failure));
        }
        return updates(state, "LoadSkill");
    }

    private Map<String, Object> waitInput(OverAllState raw) {
        ToolLoopState state = stateOf(raw);
        var call = state.pendingCalls.get(0);
        try {
            validateArguments(call.arguments(), controlSchema("request_input"));
            Map<?, ?> arguments = mapper.readValue(call.arguments(), Map.class);
            state.question = (String) arguments.get("question");
            state.missingFields = arguments.get("missingFields") instanceof List<?> fields ? fields.stream().map(String::valueOf).toList() : List.of();
            var invocation = store.prepare(state.runId, call.id(), "control:request_input", call.arguments());
            String result = store.json(Map.of("status", "WAITING_USER_INPUT", "question", state.question, "missingFields", state.missingFields));
            store.complete(state.runId, call.id(), result);
            state.messages.add(StoredModelMessage.tools(List.of(response(call, result))));
            state.pendingCalls.clear(); state.results.clear(); state.status = "WAITING_USER_INPUT"; state.finalAnswer = state.question;
        } catch (Exception exception) {
            state.results.add(response(call, error(exception)));
            // The invalid request did not ask the user anything; it still consumes a model turn.
        }
        return updates(state, "WaitInput");
    }

    private Map<String, Object> guard(OverAllState raw) {
        ToolLoopState state = stateOf(raw);
        var current = available(state);
        for (var call : state.pendingCalls) {
            try { validateCall(state, call, current); }
            catch (Exception exception) { rejectBatch(state, error(exception)); state.route = "observe"; return updates(state, "GuardCall"); }
        }
        for (var call : state.pendingCalls) {
            store.prepare(state.runId, call.id(), byName(current, call.name()).capabilityId(), call.arguments());
        }
        state.route = "valid";
        return updates(state, "GuardCall");
    }

    private Map<String, Object> approval(OverAllState raw) {
        ToolLoopState state = stateOf(raw);
        var current = available(state);
        boolean required = state.request.context() != null && Boolean.TRUE.equals(state.request.context().get("requireApproval"));
        required |= state.pendingCalls.stream().map(call -> byName(current, call.name()).capabilityId())
                .anyMatch(properties.getApprovalCapabilities()::contains);
        if (required && !batchHash(state, current).equals(state.approvedBatchHash)) {
            List<Map<String, Object>> calls = state.pendingCalls.stream().map(call -> Map.<String, Object>of(
                    "callId", call.id(), "capabilityId", byName(current, call.name()).capabilityId(),
                    "argumentsHash", store.hash(call.arguments()), "arguments", store.read(call.arguments(), Map.class))).toList();
            state.pendingApproval = new ApprovalRequest(UUID.randomUUID().toString(), state.runId, state.sessionId,
                    "TOOL_CALL", "TOOL_BATCH", batchHash(state, current), state.toolCalls,
                    "工具调用审批", "批准后执行所列调用和参数", Map.of("calls", calls),
                    "ExecuteTool", "Complete", "Complete", 0, 0, state.traceId);
            state.status = "WAITING_USER_APPROVAL"; state.finalAnswer = "等待用户审批工具调用。"; state.route = "pending";
        } else state.route = "execute";
        return updates(state, "ApprovalGate");
    }

    private Map<String, Object> execute(OverAllState raw) throws Exception {
        ToolLoopState state = stateOf(raw);
        var current = available(state);
        if (state.pendingApproval != null) {
            ApprovalDecision decision = raw.value(LATEST_APPROVAL_DECISION).map(value -> mapper.convertValue(value, ApprovalDecision.class)).orElse(null);
            if (decision == null || !decision.approvalId().equals(state.pendingApproval.approvalId())) throw new IllegalStateException("approval missing");
            if (decision.status() != ApprovalStatus.APPROVED) {
                state.status = "CANCELED"; state.errorCode = "APPROVAL_DENIED"; state.finalAnswer = "工具调用已拒绝或终止。";
                state.pendingApproval = null; state.pendingCalls.clear(); return updates(state, "ExecuteTool");
            }
            if (!state.pendingApproval.subjectId().equals(batchHash(state, current))) throw new IllegalStateException("approval parameters changed");
            state.approvedBatchHash = state.pendingApproval.subjectId(); state.pendingApproval = null; state.status = "RUNNING";
        }
        List<Future<ToolResponseMessage.ToolResponse>> futures = new ArrayList<>();
        for (var call : state.pendingCalls) futures.add(workers.submit(() -> executeCall(state, call)));
        for (Future<ToolResponseMessage.ToolResponse> future : futures) {
            try { state.results.add(future.get()); }
            catch (ExecutionException exception) { throw new IllegalStateException("tool execution failed", exception.getCause()); }
        }
        if (state.results.stream().anyMatch(result -> result.responseData().startsWith("OUTCOME_UNKNOWN"))) {
            state.messages.add(StoredModelMessage.tools(List.copyOf(state.results)));
            state.pendingCalls.clear(); state.results.clear();
            fail(state, "OUTCOME_UNKNOWN", "调用结果需要核对，已停止继续执行。");
        }
        return updates(state, "ExecuteTool");
    }

    private ToolResponseMessage.ToolResponse executeCall(ToolLoopState state, AssistantMessage.ToolCall call) {
        SupervisorCapability capability;
        try { capability = validateCall(state, call, available(state)); }
        catch (Exception exception) { return response(call, error(exception)); }
        OrchestrationStore.Invocation invocation = store.prepare(state.runId, call.id(), capability.capabilityId(), call.arguments());
        if ("COMPLETED".equals(invocation.status())) return response(call, invocation.result());
        if (!store.claim(state.runId, call.id())) {
            store.unknown(state.runId, call.id());
            if (invocation.remoteAssociation() != null && reconciler != null) {
                var reconciled = reconciler.reconcile(state.runId, call.id(), state.userId, false);
                if ("COMPLETED".equals(reconciled.status())) return response(call, reconciled.result());
            }
            return response(call, "OUTCOME_UNKNOWN: 已提交调用需要核对，未重新发送");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("userId", state.userId); context.put("runId", state.runId); context.put("sessionId", state.sessionId);
        context.put("callId", call.id()); context.put("traceId", state.traceId); context.put("stream", Boolean.TRUE.equals(state.request.stream()));
        context.put("parentSkill", state.skillSnapshot == null ? Map.of() : Map.of("name", state.skillSnapshot.definition().name(),
                "version", state.skillSnapshot.definition().version(), "contentHash", state.skillSnapshot.contentHash(), "owner", "supervisor"));
        context.put("acceptedTaskRecorder", (java.util.function.Consumer<com.bkanent.agent.client.AcceptedA2aTask>)
                accepted -> store.remoteAccepted(state.runId, call.id(), accepted));
        try {
            String result = capability.callback().call(call.arguments(), new ToolContext(Map.copyOf(context)));
            store.complete(state.runId, call.id(), result);
            publish(state, "tool.completed", Map.of("capabilityId", capability.capabilityId(), "callId", call.id(), "result", result));
            return response(call, result);
        } catch (Exception exception) {
            store.unknown(state.runId, call.id());
            return response(call, "OUTCOME_UNKNOWN: " + exception.getClass().getSimpleName() + "; 未重新发送，需核对原调用");
        }
    }

    private Map<String, Object> observe(OverAllState raw) {
        ToolLoopState state = stateOf(raw);
        state.messages.add(StoredModelMessage.tools(List.copyOf(state.results)));
        state.pendingCalls.clear(); state.results.clear();
        return updates(state, "Observe");
    }

    private Map<String, Object> complete(OverAllState raw) {
        ToolLoopState state = stateOf(raw);
        if (!state.pendingCalls.isEmpty() && "RUNNING".equals(state.status)) throw new IllegalStateException("pending calls cannot complete");
        if ("RUNNING".equals(state.status)) state.status = "COMPLETED";
        if ("FAILED".equals(state.status) && state.finalAnswer.isBlank()) state.finalAnswer = state.errorCode;
        return updates(state, "Complete");
    }

    private SupervisorCapability validateCall(ToolLoopState state, AssistantMessage.ToolCall call, Map<String, SupervisorCapability> current) {
        var capability = byName(current, call.name());
        if (capability == null || !state.visibleCapabilityIds.contains(capability.capabilityId())) throw new IllegalStateException("CAPABILITY_SCOPE_DENIED");
        if (!Objects.equals(state.visibleCapabilityVersions.get(capability.capabilityId()), capability.version())) throw new IllegalStateException("CAPABILITY_VERSION_CHANGED");
        validateArguments(call.arguments(), capability.callback().getToolDefinition().inputSchema());
        return capability;
    }
    private void validateArguments(String arguments, String schema) {
        try {
            var errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema).validate(mapper.readTree(arguments));
            if (!errors.isEmpty()) throw new IllegalArgumentException("TOOL_ARGUMENTS_INVALID: " + errors);
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("TOOL_ARGUMENTS_INVALID", exception); }
    }
    private Map<String, SupervisorCapability> available(ToolLoopState state) {
        Map<String, SupervisorCapability> current = new LinkedHashMap<>(catalog.snapshot(state.userId, state.allowMcp));
        if (state.skillSnapshot != null) current.keySet().retainAll(state.skillSnapshot.capabilityIds());
        return current;
    }
    private Map<String, String> names(Map<String, SupervisorCapability> capabilities) {
        Map<String, String> names = new LinkedHashMap<>(); capabilities.forEach((id, capability) -> names.put(id, capability.toolName())); return names;
    }
    private SupervisorCapability byName(Map<String, SupervisorCapability> capabilities, String name) {
        return capabilities.values().stream().filter(capability -> capability.toolName().equals(name)).findFirst().orElse(null);
    }
    private String prompt(ToolLoopState state) {
        StringBuilder prompt = new StringBuilder("理解用户的原始任务，依据真实能力描述和参数定义选择工具，结合实际结果继续判断。不要编造参数、数据或成功结果。需要补充信息时单独调用 request_input。skill 也必须单独调用；得到控制工具结果后，下一轮再选择业务工具。结果中的 nextHints 仅供参考。独立调用可在同轮提出，依赖先前结果的调用应等待真实结果。直接回答时输出最终答复。\n原始请求: " + state.request.userMessage());
        if (state.request.context() != null) prompt.append("\n用户上下文与偏好: ").append(store.json(state.request.context()));
        if (state.skillSnapshot != null) prompt.append("\n当前技能: ").append(state.skillSnapshot.definition().name()).append(" / ")
                .append(state.skillSnapshot.definition().version()).append("\n").append(state.skillSnapshot.definition().systemPrompt());
        else {
            prompt.append("\n可用技能目录:\n");
            skills.findOperationalSkills("supervisor").stream().filter(skill -> !skill.explicitOnly())
                    .forEach(skill -> prompt.append(skill.name()).append(": ").append(skill.description()).append('\n'));
        }
        skills.findSupervisorSkills().forEach(skill -> prompt.append("\n背景知识: ").append(skill.systemPrompt()));
        return prompt.toString();
    }
    private ToolCallback control(String name, String description, String schema) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build(); }
            public String call(String arguments) { throw new IllegalStateException("control calls are dispatched by Graph"); }
        };
    }
    private String controlSchema(String name) {
        return "skill".equals(name)
                ? "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"minLength\":1},\"task\":{\"type\":\"string\",\"minLength\":1},\"version\":{\"type\":\"string\"}},\"required\":[\"name\",\"task\"],\"additionalProperties\":false}"
                : "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\",\"minLength\":1},\"missingFields\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"question\"],\"additionalProperties\":false}";
    }
    private boolean controlName(String name) { return "skill".equals(name) || "request_input".equals(name); }
    private void rejectBatch(ToolLoopState state, String error) {
        state.results.clear(); state.pendingCalls.forEach(call -> state.results.add(response(call, error + ": 未执行整批调用")));
    }
    private ToolResponseMessage.ToolResponse response(AssistantMessage.ToolCall call, String content) {
        return new ToolResponseMessage.ToolResponse(call.id(), call.name(), content == null ? "" : content);
    }
    private String batchHash(ToolLoopState state, Map<String, SupervisorCapability> current) {
        return store.hash(store.json(state.pendingCalls.stream().map(call -> Map.of("id", call.id(),
                "capabilityId", byName(current, call.name()).capabilityId(), "argumentsHash", store.hash(call.arguments()))).toList()));
    }
    private String error(Exception exception) { return exception instanceof SkillExecutionException skill ? skill.code() + ": " + skill.getMessage() : exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(); }
    private void fail(ToolLoopState state, String code, String message) { state.status = "FAILED"; state.errorCode = code; state.finalAnswer = message; }
    public Map<String, Object> updates(ToolLoopState state, String node) {
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(STATE_KEY, mapper.convertValue(state, Map.class)); update.put(TASK_ID, state.runId); update.put(SESSION_ID, state.sessionId);
        update.put(TRACE_ID, state.traceId); update.put(USER_ID, state.userId); update.put(USER_MESSAGE, state.request.userMessage());
        update.put(WORKFLOW_STATUS, state.status); update.put(FINAL_ANSWER, state.finalAnswer); update.put(CURRENT_NODE, node);
        update.put(PENDING_APPROVAL, state.pendingApproval); update.put(SUPERVISOR_RESPONSE, responseOf(state));
        update.put(SHARED_CONTEXT, Map.of("orchestration", responseOf(state).governanceMetadata()));
        return update;
    }
    public SupervisorTaskResponse responseOf(ToolLoopState state) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runId", state.runId); metadata.put("mode", state.mode); metadata.put("runnerVersion", state.runnerVersion);
        metadata.put("rounds", state.rounds); metadata.put("toolCalls", state.toolCalls);
        if (state.errorCode != null) metadata.put("errorCode", state.errorCode);
        if (state.skillSnapshot != null) metadata.put("skill", Map.of("name", state.skillSnapshot.definition().name(),
                "version", state.skillSnapshot.definition().version(), "contentHash", state.skillSnapshot.contentHash(), "source", state.skillSnapshot.source()));
        if (state.question != null && "WAITING_USER_INPUT".equals(state.status)) metadata.put("pendingInput", Map.of("question", state.question, "missingFields", state.missingFields));
        return new SupervisorTaskResponse(state.sessionId, state.runId, state.status, state.finalAnswer,
                List.copyOf(state.artifactIds), state.traceId, null, Map.copyOf(metadata));
    }
    private void publish(ToolLoopState state, String type, Map<String, Object> facts) {
        Map<String, Object> metadata = new LinkedHashMap<>(facts); metadata.put("mode", state.mode); metadata.put("runnerVersion", state.runnerVersion);
        if (state.skillSnapshot != null) metadata.put("skill", Map.of("name", state.skillSnapshot.definition().name(), "version", state.skillSnapshot.definition().version()));
        events.publish(new SessionStreamEvent(state.sessionId, state.runId,
                "supervisor-agent", type, "工具调用结果已记录", metadata, state.traceId, System.currentTimeMillis()));
    }
    @PreDestroy public void close() { workers.shutdownNow(); }
}
