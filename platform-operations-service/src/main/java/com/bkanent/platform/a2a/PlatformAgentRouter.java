package com.bkanent.platform.a2a;

import com.bkanent.common.a2a.A2aCredentialContext;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.spec.JSONRPCError;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** Routes one public Agent to isolated execution branches after an exact authorization check. */
public final class PlatformAgentRouter implements AgentExecutor {
    public static final String METADATA_KEY = "platformCapability";
    private static final Map<String, String> OPERATIONS = Map.of(
            "analytics", "agent.workflow.invoke.platform-operations.analytics",
            "review", "agent.workflow.invoke.platform-operations.review",
            "knowledge", "agent.workflow.invoke.platform-operations.knowledge");

    private final Map<String, AgentExecutor> branches;
    private final Consumer<String> requireOperation;
    private final ConcurrentMap<String, AgentExecutor> active = new ConcurrentHashMap<>();

    public PlatformAgentRouter(AgentExecutor analytics, AgentExecutor review, AgentExecutor knowledge) {
        this(analytics, review, knowledge, A2aCredentialContext::requireOperation);
    }

    PlatformAgentRouter(AgentExecutor analytics, AgentExecutor review, AgentExecutor knowledge,
                        Consumer<String> requireOperation) {
        this.branches = Map.of("analytics", Objects.requireNonNull(analytics),
                "review", Objects.requireNonNull(review), "knowledge", Objects.requireNonNull(knowledge));
        this.requireOperation = Objects.requireNonNull(requireOperation);
    }

    @Override
    public void execute(RequestContext context, EventQueue events) throws JSONRPCError {
        if (context == null || context.getParams() == null || context.getParams().metadata() == null
                || context.getTaskId() == null || context.getTaskId().isBlank()) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT);
        }
        Object selection = context.getParams().metadata().get(METADATA_KEY);
        if (!(selection instanceof String capability) || !OPERATIONS.containsKey(capability)) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT);
        }
        requireOperation.accept(OPERATIONS.get(capability));
        AgentExecutor branch = branches.get(capability);
        if (active.putIfAbsent(context.getTaskId(), branch) != null) {
            throw new RagException(RagErrorCode.FORBIDDEN);
        }
        try {
            branch.execute(context, events);
        } finally {
            active.remove(context.getTaskId(), branch);
        }
    }

    @Override
    public void cancel(RequestContext context, EventQueue events) throws JSONRPCError {
        if (context == null || context.getTaskId() == null || context.getTaskId().isBlank()) {
            throw new RagException(RagErrorCode.INVALID_ARGUMENT);
        }
        // The underlying executor independently verifies the task-bound cancellation lease.
        AgentExecutor branch = active.getOrDefault(context.getTaskId(), branches.get("analytics"));
        branch.cancel(context, events);
    }
}
