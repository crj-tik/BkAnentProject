package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.DataPart;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.Disposable;

/**
 * Project-side official A2A executor that adds DataPart and terminal-result semantics
 * missing from the Alibaba starter's default GraphAgentExecutor.
 */
public final class OfficialA2aAgentExecutor implements AgentExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(OfficialA2aAgentExecutor.class);
    private static final java.util.Set<String> IGNORED_NODES = java.util.Set.of(
            "preLlm", "postLlm", "preTool", "tool", "postTool"
    );

    private final ReactAgent agent;
    private final long streamTimeoutMs;
    private final A2aOutputPolicy outputPolicy;
    private final A2aInputParser inputParser;
    private final A2aOutputNormalizer outputNormalizer;
    private final ConcurrentMap<String, ExecutionState> executions = new ConcurrentHashMap<>();
    private final com.bkanent.common.skill.core.SkillRegistry skillRegistry;
    private final String skillOwner;
    private final Map<String, String> capabilities;

    public OfficialA2aAgentExecutor(ReactAgent agent, ObjectMapper objectMapper, A2aOutputPolicy outputPolicy) {
        this(agent, objectMapper, outputPolicy, null, null, List.of());
    }

    public OfficialA2aAgentExecutor(ReactAgent agent, ObjectMapper objectMapper, A2aOutputPolicy outputPolicy,
                                  com.bkanent.common.skill.core.SkillRegistry skillRegistry, String skillOwner,
                                  List<org.springframework.ai.tool.ToolCallback> tools) {
        this(agent, objectMapper, outputPolicy, skillRegistry, skillOwner, tools, 120_000);
    }

    public OfficialA2aAgentExecutor(ReactAgent agent, ObjectMapper objectMapper, A2aOutputPolicy outputPolicy,
                                  com.bkanent.common.skill.core.SkillRegistry skillRegistry, String skillOwner,
                                  List<org.springframework.ai.tool.ToolCallback> tools, long streamTimeoutMs) {
        if (streamTimeoutMs < 1) throw new IllegalArgumentException("stream timeout must be positive");
        this.streamTimeoutMs = streamTimeoutMs;
        this.agent = agent;
        this.outputPolicy = outputPolicy;
        this.inputParser = new A2aInputParser(objectMapper);
        this.outputNormalizer = new A2aOutputNormalizer(objectMapper);
        this.skillRegistry = skillRegistry;
        this.skillOwner = skillOwner;
        Map<String, String> available = new java.util.LinkedHashMap<>();
        tools.stream().map(tool -> tool.getToolDefinition().name()).filter(name -> !"skill".equals(name))
                .forEach(name -> available.put(com.bkanent.common.agent.CapabilityId.local(name).value(), name));
        this.capabilities = Map.copyOf(available);
    }

    @Override
    public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        TaskUpdater updater = new TaskUpdater(context, eventQueue);
        ExecutionState execution = new ExecutionState(updater);
        ExecutionState previous = executions.put(context.getTaskId(), execution);
        if (previous != null) {
            previous.cancelled.set(true);
        }
        try {
            A2aInput input = inputParser.parse(context, outputPolicy);
            var skillExecution = prepareSkillExecution(input);
            execution.skillExecution = skillExecution;
            if (execution.cancelled.get()) {
                cancelExecution(execution, "CANCELED");
                return;
            }
            updater.startWork();
            if (isStreaming(context)) {
                executeStream(context, input, execution);
            }
            else {
                executeBlocking(context, input, execution);
            }
        }
        catch (A2aInputException exception) {
            failExecution(execution, exception.code(), exception.getMessage());
        }
        catch (com.bkanent.common.skill.runtime.SkillExecutionException exception) {
            failExecution(execution, exception.code(), exception.getMessage());
        }
        catch (Exception exception) {
            LOGGER.warn("Official A2A SubAgent execution failed for task {}", context.getTaskId(), exception);
            failExecution(execution, "EXECUTION_FAILED", "SubAgent execution failed");
        }
        finally {
            executions.remove(context.getTaskId(), execution);
        }
    }

    @Override
    public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
        String taskId = context.getTaskId();
        ExecutionState execution = executions.get(taskId);
        if (execution == null) {
            TaskUpdater updater = new TaskUpdater(context, eventQueue);
            updater.cancel(updater.newAgentMessage(List.of(new TextPart("CANCELED")), Map.of("errorCode", "CANCELED")));
            return;
        }
        execution.cancelled.set(true);
        Disposable subscription = execution.subscription;
        if (subscription != null) {
            subscription.dispose();
        }
        CountDownLatch streamFinished = execution.streamFinished;
        if (streamFinished != null) {
            streamFinished.countDown();
        }
        cancelExecution(execution, "CANCELED");
    }

    private void executeBlocking(RequestContext context, A2aInput input, ExecutionState execution)
            throws Exception {
        if (execution.terminal.get()) {
            return;
        }
        var result = agent.invoke(input.instruction(), runnableConfig(context, input, execution));
        if (execution.cancelled.get() || execution.terminal.get()) {
            return;
        }
        String output = result.map(this::extractOutput).orElse("");
        completeExecution(context, execution, output, input.jsonOutput());
    }

    private void executeStream(RequestContext context, A2aInput input, ExecutionState execution)
            throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        execution.streamFinished = finished;
        StringBuilder output = new StringBuilder();
        AtomicInteger progressNumber = new AtomicInteger();
        Flux<NodeOutput> stream = agent.stream(input.instruction(), runnableConfig(context, input, execution));
        Disposable subscription = stream.subscribe(nodeOutput -> {
                    if (execution.cancelled.get() || execution.terminal.get()
                            || nodeOutput.isSTART() || nodeOutput.isEND()
                            || IGNORED_NODES.contains(nodeOutput.node())) {
                        return;
                    }
                    if (!(nodeOutput instanceof StreamingOutput<?> streamingOutput)) {
                        return;
                    }
                    String chunk = streamingOutput.chunk();
                    if (!StringUtils.hasText(chunk)) {
                        return;
                    }
                    appendBounded(output, chunk, outputPolicy.maxOutputChars());
                    execution.updater.addArtifact(
                            List.of(new TextPart(chunk)),
                            progressArtifactId(context.getTaskId(), progressNumber.incrementAndGet()),
                            "progress",
                            Map.of("outputMode", A2aOutputPolicy.TEXT_MODE, "terminal", false));
                },
                error -> {
                    try {
                        failExecution(execution, "EXECUTION_FAILED", "SubAgent streaming execution failed");
                    }
                    finally {
                        finished.countDown();
                    }
                },
                () -> {
                    try {
                        if (!execution.cancelled.get() && !execution.terminal.get()) {
                            completeExecution(context, execution, output.toString(), input.jsonOutput());
                        }
                    }
                    finally {
                        finished.countDown();
                    }
                });
        execution.subscription = subscription;
        if (execution.cancelled.get()) {
            subscription.dispose();
            finished.countDown();
        }
        try {
            if (!finished.await(streamTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                subscription.dispose();
                failExecution(execution, "EXECUTION_TIMEOUT", "SubAgent streaming execution timed out");
            }
        } catch (InterruptedException exception) {
            subscription.dispose();
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            subscription.dispose();
            execution.streamFinished = null;
        }
    }

    private void completeExecution(RequestContext context, ExecutionState execution,
                                   String rawOutput, boolean jsonOutput) {
        if (execution.cancelled.get() || !execution.terminal.compareAndSet(false, true)) {
            return;
        }
        try {
            A2aOutput output = outputNormalizer.normalize(rawOutput, outputPolicy, jsonOutput);
            List<Part<?>> parts = output.structured()
                    ? List.of(new DataPart(output.data(), Map.of(
                    "contentType", String.valueOf(output.data().get("contentType")),
                    "outputMode", A2aOutputPolicy.JSON_MODE,
                    "terminal", true)), new TextPart(output.summary()))
                    : List.of(new TextPart(output.text()));
            execution.updater.addArtifact(parts, terminalArtifactId(context.getTaskId()), "result",
                    Map.of("contentType", output.structured()
                                    ? String.valueOf(output.data().get("contentType")) : outputPolicy.contentType(),
                            "outputMode", output.outputMode(), "terminal", true));
            execution.updater.complete();
        }
        catch (A2aOutputException exception) {
            execution.terminal.set(false);
            failExecution(execution, exception.code(), exception.getMessage());
        }
        catch (RuntimeException exception) {
            execution.terminal.set(false);
            failExecution(execution, "INVALID_STRUCTURED_OUTPUT", "SubAgent result cannot be serialized");
        }
    }

    private void failExecution(ExecutionState execution, String code, String message) {
        if (execution.cancelled.get() && !"CANCELED".equals(code)) {
            cancelExecution(execution, "CANCELED");
            return;
        }
        if (!execution.terminal.compareAndSet(false, true)) {
            return;
        }
        String safeMessage = StringUtils.hasText(message) ? message : code;
        execution.updater.fail(execution.updater.newAgentMessage(
                List.of(new TextPart(code + ": " + safeMessage)), Map.of("errorCode", code)));
    }

    private void cancelExecution(ExecutionState execution, String code) {
        if (!execution.terminal.compareAndSet(false, true)) {
            return;
        }
        execution.updater.cancel(execution.updater.newAgentMessage(
                List.of(new TextPart(code)), Map.of("errorCode", code)));
    }

    private boolean isStreaming(RequestContext context) {
        return context.getParams() != null && context.getParams().metadata() != null
                && Boolean.TRUE.equals(context.getParams().metadata().get("isStreaming"));
    }

    private com.bkanent.common.skill.runtime.SkillExecutionContext prepareSkillExecution(A2aInput input) {
        Object raw = input.metadata().get("supervisor");
        Object selected = raw instanceof Map<?, ?> supervisor ? supervisor.get("skillSelection") : null;
        com.bkanent.common.agent.SkillSelection selection = null;
        if (selected != null) {
            if (skillRegistry == null) {
                throw new com.bkanent.common.skill.runtime.SkillExecutionException(
                        "EXPLICIT_SKILL_UNSUPPORTED", "target does not support explicit selection");
            }
            if (!(selected instanceof Map<?, ?> fields) || !"explicit".equals(fields.get("mode"))) {
                throw new com.bkanent.common.skill.runtime.SkillExecutionException("SKILL_POLICY_INVALID", "invalid selection");
            }
            try {
                selection = new com.bkanent.common.agent.SkillSelection(string(fields.get("name")),
                        string(fields.get("version")), string(fields.get("contentHash")), string(fields.get("owner")));
            } catch (IllegalArgumentException exception) {
                throw new com.bkanent.common.skill.runtime.SkillExecutionException("SKILL_POLICY_INVALID", "skill name required");
            }
        }
        return skillRegistry == null ? null : new com.bkanent.common.skill.runtime.SkillExecutionContext(
                skillRegistry, skillOwner, input.instruction(), capabilities, selection);
    }

    private String string(Object value) { return value instanceof String text ? text : null; }

    private RunnableConfig runnableConfig(RequestContext context, A2aInput input, ExecutionState execution) {
        RunnableConfig.Builder builder = RunnableConfig.builder();
        Object threadId = input.metadata().get("threadId");
        if (threadId instanceof String text && StringUtils.hasText(text)) {
            builder.threadId(text);
        }
        else if (StringUtils.hasText(context.getTaskId())) {
            builder.threadId(context.getTaskId());
        }
        input.metadata().forEach(builder::addMetadata);
        if (execution.skillExecution != null) {
            builder.addMetadata(com.bkanent.common.skill.runtime.SkillExecutionContext.KEY, execution.skillExecution);
        }
        builder.addMetadata("a2aStructuredInput", input.structuredContext());
        builder.addMetadata("a2aOutputMode", input.jsonOutput()
                ? A2aOutputPolicy.JSON_MODE : A2aOutputPolicy.TEXT_MODE);
        return builder.build();
    }

    private String extractOutput(OverAllState state) {
        String outputKey = agent.getOutputKey();
        Object value = outputKey == null ? null : state.value(outputKey).orElse(null);
        if (value instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        }
        return value == null ? "" : String.valueOf(value);
    }

    private void appendBounded(StringBuilder target, String value, int maxLength) {
        int remaining = maxLength - target.length();
        if (remaining > 0) {
            target.append(value, 0, Math.min(remaining, value.length()));
        }
    }

    private String progressArtifactId(String taskId, int number) {
        return artifactPrefix(taskId) + ":progress:" + number;
    }

    private String terminalArtifactId(String taskId) {
        return artifactPrefix(taskId) + ":result";
    }

    private String artifactPrefix(String taskId) {
        String agentName = agent.name() == null ? "subagent" : agent.name();
        String safeAgent = agentName.replaceAll("[^A-Za-z0-9._-]", "_");
        String safeTask = (taskId == null ? "unknown" : taskId).replaceAll("[^A-Za-z0-9._-]", "_");
        return safeAgent + ":" + safeTask;
    }

    private static final class ExecutionState {
        private final TaskUpdater updater;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private volatile Disposable subscription;
        private volatile CountDownLatch streamFinished;
        private com.bkanent.common.skill.runtime.SkillExecutionContext skillExecution;

        private ExecutionState(TaskUpdater updater) {
            this.updater = updater;
        }
    }
}
