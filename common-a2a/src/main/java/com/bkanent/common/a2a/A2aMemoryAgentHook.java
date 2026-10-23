package com.bkanent.common.a2a;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import com.bkanent.common.agent.DurableMemoryExportDirective;
import com.bkanent.common.memory.*;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.memory.runtime.MemoryCompactionCoordinator;
import com.bkanent.memory.runtime.MemoryRpcClient;
import com.bkanent.memory.runtime.MemoryWorkingSetCache;
import com.bkanent.memory.runtime.MemoryWorkingSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.dubbo.config.ReferenceConfig;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * One explicit AgentHook for the A2A lifecycle. It loads once before the Agent,
 * archives only after the full Agent turn, and never participates in progress callbacks.
 */
public final class A2aMemoryAgentHook extends AgentHook {
    public static final String INVOCATION_METADATA_KEY = "__bkanent_a2a_memory_invocation_v1";
    private static final String MESSAGE_SCHEMA = "a2a.message.v2";
    private static final Pattern CREDENTIAL_TEXT = Pattern.compile(
            "(?i)(?:\\bBearer\\s+\\S+|\\b(?:password|passwd|access[_-]?token|refresh[_-]?token|client[_-]?secret|authorization)\\b\\s*[:=]\\s*\\S+|\\beyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,})");
    private static final Set<String> SENSITIVE_JSON_KEY = Set.of("token", "password", "secret", "beacon", "authorization", "desensitizationmap");

    private final String caller;
    private final String domain;
    private final String agentId;
    private final Supplier<MemoryV2RpcService> rpcProvider;
    private final MemoryWorkingSetCache cache;
    private final A2aMemoryProperties properties;
    private final ChatModel summaryModel;
    private final ObjectMapper mapper;
    private final ExecutorService summaryExecutor;
    private final MemoryCompactionCoordinator compaction = new MemoryCompactionCoordinator();

    A2aMemoryAgentHook(String caller, String domain, String agentId, Supplier<MemoryV2RpcService> rpcProvider,
                       MemoryWorkingSetCache cache, A2aMemoryProperties properties, ChatModel summaryModel,
                       ObjectMapper mapper, ExecutorService summaryExecutor) {
        this.caller = requireId(caller);
        this.domain = requireId(domain);
        this.agentId = requireId(agentId);
        this.rpcProvider = Objects.requireNonNull(rpcProvider);
        this.cache = Objects.requireNonNull(cache);
        this.properties = Objects.requireNonNull(properties);
        this.summaryModel = Objects.requireNonNull(summaryModel);
        this.mapper = Objects.requireNonNull(mapper);
        this.summaryExecutor = Objects.requireNonNull(summaryExecutor);
    }

    A2aMemoryInvocation invocation(String taskId, String contextId) {
        return new A2aMemoryInvocation(taskId, contextId, properties.isPromptReadEnabled(), properties.isDeliveryEnabled());
    }

    A2aMemoryInvocation invocation(String taskId, String contextId, DurableMemoryExportDirective exportDirective) {
        return new A2aMemoryInvocation(taskId, contextId, properties.isPromptReadEnabled(), properties.isDeliveryEnabled(), exportDirective);
    }

    @Override public String getName() { return "a2aMemoryLifecycle"; }
    @Override public HookPosition[] getHookPositions() { return new HookPosition[]{HookPosition.BEFORE_AGENT, HookPosition.AFTER_AGENT}; }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        A2aMemoryInvocation invocation = invocation(config);
        if (invocation == null) return CompletableFuture.completedFuture(Map.of());
        boolean promptReadEnabled = properties.isPromptReadEnabled();
        if (!promptReadEnabled && !invocation.deliveryEnabledAtStart()) return CompletableFuture.completedFuture(Map.of());
        try {
            requireThread(invocation, config);
            List<Message> original = messages(state);
            invocation.insertionIndex(A2aMemoryInvocation.firstNonSystem(original));
            invocation.currentInputCount(original.size() - invocation.insertionIndex());
            try (var ignored = A2aCredentialContext.openTask(invocation.taskId())) {
                var lease = A2aCredentialContext.required();
                long ownerId = authenticatedOwner(lease);
                if (!StringUtils.hasText(invocation.contextId())) throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT);
                MemoryContextKey key = new MemoryContextKey(Long.toString(ownerId), domain, agentId,
                        stableId("a2a-context-", invocation.contextId()));
                invocation.contextKey(key);
                if (!promptReadEnabled) {
                    invocation.memoryStatus("DELIVERY_ONLY");
                    invocation.archiveStatus("PENDING");
                    invocation.prepared(true);
                    return CompletableFuture.completedFuture(Map.of());
                }
                MemoryRpcClient client = client(invocation.taskId(), key, lease);
                String runId = stableId("a2a-run-", invocation.taskId());
                MemoryWorkingSession session = new MemoryWorkingSession(client, cache, key, runId);
                invocation.session(session);
                session.check();
                maybeCompact(invocation, session, original, lease);
                session.check();
                MemoryWorkingSetResponse snapshot = session.snapshot();
                List<Message> history = history(snapshot);
                if (estimate(history, original) > properties.getHardTokens()) {
                    throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
                }
                invocation.privateMaterialLoaded(!history.isEmpty());
                invocation.memoryStatus(history.isEmpty() ? "NO_MATCH" : "APPLIED");
                invocation.historyCount(history.size());
                invocation.prepared(true);
                if (history.isEmpty()) return CompletableFuture.completedFuture(noMatchUpdate(invocation, original));
                List<Message> merged = new ArrayList<>(original.size() + history.size());
                merged.addAll(original.subList(0, invocation.insertionIndex()));
                merged.addAll(history);
                merged.addAll(original.subList(invocation.insertionIndex(), original.size()));
                return CompletableFuture.completedFuture(Map.of("messages", ReplaceAllWith.of(merged)));
            }
        } catch (MemoryException exception) {
            return promptReadEnabled ? degradeOrFail(invocation, state, exception)
                    : deliveryOnlyFailure(invocation, exception);
        } catch (RagException exception) {
            MemoryException mapped = memoryException(exception);
            return promptReadEnabled ? degradeOrFail(invocation, state, mapped)
                    : deliveryOnlyFailure(invocation, mapped);
        } catch (RuntimeException exception) {
            MemoryException unavailable = new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
            return promptReadEnabled ? degradeOrFail(invocation, state, unavailable)
                    : deliveryOnlyFailure(invocation, unavailable);
        }
    }

    private Map<String, Object> noMatchUpdate(A2aMemoryInvocation invocation, List<Message> original) {
        // Treat the clarification instruction as the one system-history slot so AFTER_AGENT archives only this turn.
        invocation.historyCount(1);
        List<Message> assembled = new ArrayList<>(original.size() + 1);
        assembled.addAll(original.subList(0, invocation.insertionIndex()));
        assembled.add(new SystemMessage("当前会话没有找到可验证的历史记忆。若本轮请求依赖未提供的既往对象、约定或决定，请先向用户澄清；不得根据缺失的历史内容猜测。若请求可独立完成，继续处理。"));
        assembled.addAll(original.subList(invocation.insertionIndex(), original.size()));
        return Map.of("messages", ReplaceAllWith.of(assembled));
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterAgent(OverAllState state, RunnableConfig config) {
        A2aMemoryInvocation invocation = invocation(config);
        if (invocation == null || !invocation.deliveryEnabledAtStart() || !invocation.prepared()
                || (invocation.session() == null && !"DELIVERY_ONLY".equals(invocation.memoryStatus()))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        try (var ignored = A2aCredentialContext.openTask(invocation.taskId())) {
            var lease = A2aCredentialContext.required();
            MemoryWorkingSession session = invocation.session();
            if (session == null && "DELIVERY_ONLY".equals(invocation.memoryStatus())) {
                session = new MemoryWorkingSession(client(invocation.taskId(), invocation.contextKey(), lease), cache,
                        invocation.contextKey(), stableId("a2a-run-", invocation.taskId()));
                invocation.session(session);
            }
            if (session == null) return CompletableFuture.completedFuture(Map.of());
            // Any private history used by this Agent must still be current before its result can escape.
            if (invocation.privateMaterialLoaded()) session.check();
            List<Message> all = messages(state);
            int start = invocation.currentInputStart();
            if (start < 0 || start > all.size() || invocation.currentInputCount() < 0) {
                throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
            }
            List<MemoryMessage> archived = project(invocation, session, all.subList(start, all.size()));
            if (archived.isEmpty()) {
                invocation.archiveStatus("NO_MESSAGES");
                return CompletableFuture.completedFuture(Map.of());
            }
            String permit = session.permit(archived);
            session.deliver(permit, archived);
            if (invocation.privateMaterialLoaded()) session.check();
            MemoryMessage finalAssistant = archived.stream()
                    .filter(message -> message.role() == MemoryMessage.Role.ASSISTANT && StringUtils.hasText(message.text()))
                    .reduce((first, later) -> later).orElse(null);
            invocation.currentTurnSources(finalAssistant == null ? List.of() : List.of(finalAssistant.source()));
            invocation.archiveStatus("RECORDED");
            return CompletableFuture.completedFuture(Map.of());
        } catch (MemoryException exception) {
            if (exception.code() == MemoryErrorCode.MEMORY_UNAVAILABLE) {
                invocation.archiveStatus("MEMORY_UNAVAILABLE");
                if (invocation.privateMaterialLoaded()) return CompletableFuture.failedFuture(exception);
                return CompletableFuture.completedFuture(Map.of());
            }
            if (exception.code() == MemoryErrorCode.AUTHENTICATION_FAILED
                    || exception.code() == MemoryErrorCode.AUTHENTICATION_EXPIRED
                    || exception.code() == MemoryErrorCode.FORBIDDEN) return CompletableFuture.failedFuture(exception);
            invocation.archiveStatus("NOT_RECORDED");
            return CompletableFuture.completedFuture(Map.of());
        } catch (RagException exception) {
            MemoryException mapped = memoryException(exception);
            if (mapped.code() == MemoryErrorCode.AUTHENTICATION_FAILED
                    || mapped.code() == MemoryErrorCode.AUTHENTICATION_EXPIRED
                    || mapped.code() == MemoryErrorCode.FORBIDDEN) return CompletableFuture.failedFuture(mapped);
            invocation.archiveStatus("MEMORY_UNAVAILABLE");
            if (invocation.privateMaterialLoaded()) return CompletableFuture.failedFuture(mapped);
            return CompletableFuture.completedFuture(Map.of());
        } catch (RuntimeException exception) {
            invocation.archiveStatus("NOT_RECORDED");
            if (invocation.privateMaterialLoaded()) {
                return CompletableFuture.failedFuture(new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE));
            }
            return CompletableFuture.completedFuture(Map.of());
        }
    }

    void finish(A2aMemoryInvocation invocation) {
        if (invocation == null) return;
        MemoryWorkingSession session = invocation.session();
        if (session != null) {
            try { session.close(); }
            finally { invocation.session(null); }
        }
    }

    /** Publishes only an explicit trusted-context directive and only after the current turn was archived. */
    MemoryExportReceipt publishExport(A2aMemoryInvocation invocation, A2aOutput output) {
        if (invocation == null || invocation.durableMemoryExport() == null) return null;
        if (!properties.isPromptReadEnabled()) return rememberExport(invocation, MemoryExportReceipt.failure("UNAVAILABLE"));
        if (!"RECORDED".equals(invocation.archiveStatus()) || invocation.contextKey() == null
                || invocation.currentTurnSources().isEmpty()) {
            return rememberExport(invocation, MemoryExportReceipt.failure("NOT_RECORDED"));
        }
        if (invocation.currentTurnSources().size() > 64) {
            return rememberExport(invocation, MemoryExportReceipt.failure("FAILED"));
        }

        MemorySharedExportStatusResponse staged = null;
        try (var ignored = A2aCredentialContext.openTask(invocation.taskId())) {
            A2aCredentialContext.Lease lease = A2aCredentialContext.required();
            lease.verify();
            DurableMemoryExportDirective directive = invocation.durableMemoryExport();
            MemoryContextKey context = invocation.contextKey();
            MemoryRpcClient client = client(invocation.taskId(), context, lease);
            List<MemorySourceRef> sources = invocation.currentTurnSources();
            Map<String, Object> selected = selectedExportFields(directive, output);
            MemoryJsonValue payload = memoryValue(mapper.valueToTree(selected), 0);
            String brief = directive.includeBrief() ? output.summary() : null;
            MemorySharedExportPolicy.project(brief, payload);
            MemoryShareGrant grant = new MemoryShareGrant(new MemoryGrantRef(directive.grantId(), 1), context,
                    directive.scope(), directive.matterId(), directive.purpose(), directive.recipients(),
                    directive.projections(), sources, directive.validFrom(), directive.validUntil(), MemoryShareGrant.State.ACTIVE);
            client.createShareGrant(context, grant);

            String sourceExportId = stableId("a2a-export-", List.of(context, invocation.taskId(), directive.grantId()));
            String sourceInvocationId = stableId("a2a-publish-", List.of(invocation.taskId(), directive.grantId()));
            String runId = stableId("a2a-run-", invocation.taskId());
            String taskId = stableId("a2a-task-", invocation.taskId());
            MemoryAssociation association = new MemoryAssociation(directive.matterId(), runId, taskId,
                    new MemoryAssociation.DomainSessionRef(domain, context.conversationId()));
            List<MemorySourceDependency> dependencies = sources.stream()
                    .map(source -> new MemorySourceDependency(source, null)).toList();
            staged = client.submitSharedExport(context, sourceExportId, sourceInvocationId, association,
                    grant.grant(), directive.purpose(), brief, payload, dependencies);
            MemorySharedExportStatusResponse completed = client.commitSharedExport(context, staged);
            return rememberExport(invocation, MemoryExportReceipt.from(completed));
        } catch (MemoryException exception) {
            if (staged != null && staged.state() == MemorySharedExport.State.PENDING) {
                return rememberExport(invocation, MemoryExportReceipt.from(staged));
            }
            String state = exception.code() == MemoryErrorCode.MEMORY_UNAVAILABLE
                    || exception.code() == MemoryErrorCode.AUTHENTICATION_EXPIRED ? "UNAVAILABLE" : "FAILED";
            return rememberExport(invocation, MemoryExportReceipt.failure(state));
        } catch (RuntimeException exception) {
            if (staged != null && staged.state() == MemorySharedExport.State.PENDING) {
                return rememberExport(invocation, MemoryExportReceipt.from(staged));
            }
            return rememberExport(invocation, MemoryExportReceipt.failure("UNAVAILABLE"));
        }
    }

    private MemoryExportReceipt rememberExport(A2aMemoryInvocation invocation, MemoryExportReceipt receipt) {
        invocation.exportReceipt(receipt);
        return receipt;
    }

    private Map<String, Object> selectedExportFields(DurableMemoryExportDirective directive, A2aOutput output) {
        Map<String, Object> source = output.structured() ? output.data() : Map.of("text", output.text());
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String field : directive.exportFields()) {
            if (!source.containsKey(field)) throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION);
            selected.put(field, source.get(field));
        }
        return java.util.Collections.unmodifiableMap(selected);
    }

    private CompletableFuture<Map<String, Object>> degradeOrFail(A2aMemoryInvocation invocation, OverAllState state,
                                                                  MemoryException exception) {
        finish(invocation);
        if (exception.code() != MemoryErrorCode.MEMORY_UNAVAILABLE) return CompletableFuture.failedFuture(exception);
        invocation.memoryStatus("MEMORY_UNAVAILABLE");
        invocation.archiveStatus("NOT_APPLIED");
        List<Message> original = messages(state);
        int index = A2aMemoryInvocation.firstNonSystem(original);
        invocation.insertionIndex(index);
        invocation.historyCount(1);
        invocation.currentInputCount(original.size() - index);
        invocation.prepared(true);
        List<Message> degraded = new ArrayList<>(original.size() + 1);
        degraded.addAll(original.subList(0, index));
        degraded.add(new SystemMessage("当前会话历史记忆不可用。若本轮请求依赖上下文中未提供的既往内容，请先向用户询问补充；不要猜测历史事实。"));
        degraded.addAll(original.subList(index, original.size()));
        return CompletableFuture.completedFuture(Map.of("messages", ReplaceAllWith.of(degraded)));
    }

    private CompletableFuture<Map<String, Object>> deliveryOnlyFailure(A2aMemoryInvocation invocation, MemoryException exception) {
        finish(invocation);
        invocation.memoryStatus("DELIVERY_ONLY");
        if (exception.code() != MemoryErrorCode.MEMORY_UNAVAILABLE) return CompletableFuture.failedFuture(exception);
        invocation.archiveStatus("MEMORY_UNAVAILABLE");
        return CompletableFuture.completedFuture(Map.of());
    }

    private void maybeCompact(A2aMemoryInvocation invocation, MemoryWorkingSession session, List<Message> current,
                              A2aCredentialContext.Lease lease) {
        if (!properties.isCompressionEnabled()) return;
        int estimated = estimate(history(session.snapshot()), current);
        if (estimated < properties.getSoftTokens()) return;
        int retain = properties.getRecentMessages();
        long earliestToolCall = session.snapshot().messages().stream().filter(message -> !message.toolCalls().isEmpty())
                .mapToLong(MemoryMessage::sequence).min().orElse(Long.MAX_VALUE);
        if (earliestToolCall != Long.MAX_VALUE) {
            long protectedTail = session.snapshot().version().lastSequence() - earliestToolCall + 1;
            if (protectedTail > 500) return;
            retain = Math.max(retain, (int) protectedTail);
        }
        try {
            int recentMessages = retain;
            compaction.compactStructured(session, recentMessages, MemoryCompactionCoordinator.Trigger.TOKEN_PRESSURE,
                    null, this::summaryCandidate, lease::verify);
        } catch (MemoryException exception) {
            if (exception.code() == MemoryErrorCode.AUTHENTICATION_FAILED || exception.code() == MemoryErrorCode.AUTHENTICATION_EXPIRED
                    || exception.code() == MemoryErrorCode.FORBIDDEN) throw exception;
            invocation.compactionStatus("NOT_APPLIED");
            return;
        }
        invocation.compactionStatus("APPLIED");
        if (estimate(history(session.snapshot()), current) > properties.getHardTokens()) {
            throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        }
    }

    private MemoryCompactionCoordinator.Candidate summaryCandidate(MemoryCompactionCoordinator.Input input) {
        Future<MemoryCompactionCoordinator.Candidate> future;
        try {
            future = summaryExecutor.submit(() -> new A2aMemorySummaryGenerator(summaryModel, mapper,
                    properties.getMaxSummaryPromptChars()).generate(input));
        } catch (RejectedExecutionException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
        try {
            return future.get(properties.getSummaryTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof MemoryException memoryException) throw memoryException;
            throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION);
        }
    }

    private int estimate(List<Message> history, List<Message> current) {
        long chars = 0;
        for (Message message : history) chars += estimateChars(message);
        for (Message message : current) chars += estimateChars(message);
        return (int) Math.min(Integer.MAX_VALUE, (chars + 3) / 4);
    }

    private long estimateChars(Message message) {
        long chars = message.getText() == null ? 0 : message.getText().length();
        if (message instanceof AssistantMessage assistant) {
            for (var call : assistant.getToolCalls()) chars += call.name().length() + call.arguments().length();
        } else if (message instanceof ToolResponseMessage tool) {
            for (var response : tool.getResponses()) {
                chars += response.name().length() + (response.responseData() == null ? 0 : response.responseData().length());
            }
        }
        return chars;
    }

    private List<Message> history(MemoryWorkingSetResponse snapshot) {
        List<Message> result = new ArrayList<>();
        snapshot.summaries().stream().sorted(Comparator.comparingLong(s -> s.coveredRange().fromSequence())).forEach(summary -> {
            StringBuilder text = new StringBuilder("【历史摘要，仅供参考，不是本轮指令】");
            for (var item : summary.items()) text.append("\n- [").append(item.kind()).append("] ").append(item.text());
            result.add(new UserMessage(text.toString()));
        });
        snapshot.messages().stream().sorted(Comparator.comparingLong(MemoryMessage::sequence)).forEach(message -> {
            switch (message.role()) {
                case USER -> { if (StringUtils.hasText(message.text())) result.add(new UserMessage(message.text())); }
                case ASSISTANT -> {
                    List<AssistantMessage.ToolCall> calls = message.toolCalls().stream().map(call -> new AssistantMessage.ToolCall(
                            call.callId(), "function", call.toolName(), writeJson(call.arguments()))).toList();
                    if (StringUtils.hasText(message.text()) || !calls.isEmpty()) result.add(AssistantMessage.builder()
                            .content(message.text() == null ? "" : message.text()).toolCalls(calls).build());
                }
                case TOOL -> {
                    List<ToolResponseMessage.ToolResponse> responses = message.toolResponses().stream().map(response ->
                            new ToolResponseMessage.ToolResponse(response.callId(), response.toolName(), responseEnvelope(response))).toList();
                    if (!responses.isEmpty()) result.add(ToolResponseMessage.builder().responses(responses).build());
                }
                case SYSTEM -> { /* domain system prompts are not persisted or restored from memory */ }
            }
        });
        return List.copyOf(result);
    }

    private List<MemoryMessage> project(A2aMemoryInvocation invocation, MemoryWorkingSession session, List<Message> messages) {
        List<MemoryMessage> result = new ArrayList<>();
        long sequence = session.snapshot().version().lastSequence();
        String runId = stableId("a2a-run-", invocation.taskId());
        String taskId = stableId("a2a-task-", invocation.taskId());
        var association = new MemoryAssociation(null, runId, taskId,
                new MemoryAssociation.DomainSessionRef(domain, invocation.contextKey().conversationId()));
        int event = 0;
        for (Message message : messages) {
            MemoryMessage.Role role;
            String text = hasText(message.getText()) ? safeText(message.getText()) : null;
            List<MemoryToolCall> calls = List.of();
            List<MemoryToolResponse> responses = List.of();
            if (message instanceof UserMessage) role = MemoryMessage.Role.USER;
            else if (message instanceof AssistantMessage assistant) {
                role = MemoryMessage.Role.ASSISTANT;
                calls = assistant.getToolCalls().stream().map(call -> {
                    JsonNode arguments;
                    try { arguments = mapper.readTree(call.arguments()); }
                    catch (Exception exception) { throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION); }
                    if (arguments == null || !arguments.isObject()) throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION);
                    return new MemoryToolCall(call.id(), "a2a-tool:" + call.name(), call.name(), memoryValue(arguments, 0));
                }).toList();
            } else if (message instanceof ToolResponseMessage tool) {
                role = MemoryMessage.Role.TOOL;
                text = null;
                responses = tool.getResponses().stream().map(response -> {
                    String raw = response.responseData() == null ? "" : safeText(response.responseData());
                    JsonNode data;
                    try { data = mapper.readTree(raw); }
                    catch (Exception ignored) { data = mapper.getNodeFactory().textNode(raw); }
                    return new MemoryToolResponse(response.id(), response.name(), responseState(raw), null,
                            MemoryToolResponse.DataForm.INLINE_COMPLETE, memoryValue(data, 0), null, List.of());
                }).toList();
            } else {
                // System instructions, developer prompts, and multimodal implementation details never enter memory.
                continue;
            }
            if ((text == null || text.isBlank()) && calls.isEmpty() && responses.isEmpty()) continue;
            long next = ++sequence;
            String eventId = stableId("a2a-event-", Arrays.asList(invocation.taskId(), event++, role, text, calls, responses));
            String messageId = stableId("a2a-message-", List.of(invocation.contextKey(), eventId));
            var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, invocation.contextKey(), messageId, eventId,
                    next, new MemoryRevision(1, "0".repeat(64), MESSAGE_SCHEMA));
            var draft = new MemoryMessage(messageId, invocation.contextKey(), next, association, role, text, calls, responses, source);
            String hash = MemoryCanonicalHash.messageHash(draft);
            source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, invocation.contextKey(), messageId, eventId,
                    next, new MemoryRevision(1, hash, MESSAGE_SCHEMA));
            result.add(new MemoryMessage(messageId, invocation.contextKey(), next, association, role, text, calls, responses, source));
            if (result.size() > 500) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        }
        return List.copyOf(result);
    }

    private MemoryToolResponse.ExecutionState responseState(String raw) {
        try {
            JsonNode status = mapper.readTree(raw).path("status");
            return switch (status.asText("")) {
                case "FAILED", "REJECTED" -> MemoryToolResponse.ExecutionState.FAILED;
                case "OUTCOME_UNKNOWN" -> MemoryToolResponse.ExecutionState.OUTCOME_UNKNOWN;
                case "WAITING_USER_APPROVAL" -> MemoryToolResponse.ExecutionState.WAITING_APPROVAL;
                case "PARTIAL" -> MemoryToolResponse.ExecutionState.PARTIAL;
                default -> MemoryToolResponse.ExecutionState.RETURNED;
            };
        } catch (Exception ignored) { return MemoryToolResponse.ExecutionState.RETURNED; }
    }

    private String responseEnvelope(MemoryToolResponse response) {
        try {
            return mapper.writeValueAsString(Map.of("callbackState", response.state().name(),
                    "businessSuccessVerified", false, "responseData", fromMemoryValue(response.responseData())));
        } catch (Exception exception) { throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE); }
    }

    private Object fromMemoryValue(MemoryJsonValue value) {
        if (value == null) return null;
        return switch (value.type()) {
            case NULL -> null;
            case STRING -> value.scalar();
            case BOOLEAN -> Boolean.valueOf(value.scalar());
            case NUMBER -> mapper.getNodeFactory().numberNode(new java.math.BigDecimal(value.scalar()));
            case ARRAY -> value.elements().stream().map(this::fromMemoryValue).toList();
            case OBJECT -> {
                Map<String, Object> fields = new LinkedHashMap<>();
                value.properties().forEach((key, child) -> fields.put(key, fromMemoryValue(child)));
                yield fields;
            }
        };
    }

    private MemoryJsonValue memoryValue(JsonNode node, int depth) {
        if (depth > 16) throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION);
        if (node.isObject()) {
            Map<String, MemoryJsonValue> properties = new LinkedHashMap<>();
            node.fields().forEachRemaining(field -> {
                String key = field.getKey();
                String normalized = key.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
                if (SENSITIVE_JSON_KEY.stream().anyMatch(normalized::contains)) throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION);
                properties.put(key, memoryValue(field.getValue(), depth + 1));
            });
            return MemoryJsonValue.object(properties);
        }
        if (node.isArray()) {
            List<MemoryJsonValue> elements = new ArrayList<>();
            node.forEach(item -> elements.add(memoryValue(item, depth + 1)));
            return MemoryJsonValue.array(elements);
        }
        if (node.isTextual()) return MemoryJsonValue.string(safeText(node.textValue()));
        if (node.isNumber()) return MemoryJsonValue.number(node.decimalValue().toPlainString());
        if (node.isBoolean()) return MemoryJsonValue.bool(node.booleanValue());
        return MemoryJsonValue.nil();
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION); }
    }

    private String writeJson(MemoryJsonValue value) { return writeJson(fromMemoryValue(value)); }
    private String safeText(String value) {
        if (value != null && CREDENTIAL_TEXT.matcher(value).find()) throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION);
        return value;
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    private MemoryRpcClient client(String taskId, MemoryContextKey context, A2aCredentialContext.Lease initialLease) {
        final MemoryV2RpcService rpc;
        try {
            rpc = rpcProvider.get();
            if (rpc == null) throw new IllegalStateException("memory provider unavailable");
        } catch (RuntimeException exception) {
            throw new MemoryException(MemoryErrorCode.MEMORY_UNAVAILABLE);
        }
        final java.security.PrivateKey signingKey;
        try { signingKey = properties.signingKey(); }
        catch (RuntimeException exception) { throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED); }
        return new MemoryRpcClient(rpc, caller, properties.getKeyId(), signingKey, () -> {
            try (var ignored = A2aCredentialContext.openTask(taskId)) {
                var lease = A2aCredentialContext.required();
                long ownerId = authenticatedOwner(lease);
                if (!context.ownerId().equals(Long.toString(ownerId))
                        || !lease.binding.actorId().equals(initialLease.binding.actorId())) {
                    throw new MemoryException(MemoryErrorCode.FORBIDDEN);
                }
                long expiry = Math.min(lease.expiresAt, lease.binding.credentialLeaseExpiresAtEpochSecond());
                return new MemoryRpcClient.Actor(new AuthenticatedPrincipal(ownerId), expiry);
            } catch (MemoryException exception) {
                throw exception;
            } catch (RagException exception) {
                throw memoryException(exception);
            } catch (RuntimeException exception) {
                throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
            }
        });
    }

    private long authenticatedOwner(A2aCredentialContext.Lease lease) {
        try {
            String actorId = lease.binding.actorId();
            long ownerId = Long.parseLong(actorId);
            if (ownerId <= 0 || !Long.toString(ownerId).equals(actorId)) throw new NumberFormatException();
            return ownerId;
        } catch (NumberFormatException exception) {
            throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        }
    }

    private MemoryException memoryException(RagException exception) {
        return new MemoryException(switch (exception.code()) {
            case AUTHENTICATION_EXPIRED -> MemoryErrorCode.AUTHENTICATION_EXPIRED;
            case AUTHENTICATION_FAILED -> MemoryErrorCode.AUTHENTICATION_FAILED;
            case FORBIDDEN -> MemoryErrorCode.FORBIDDEN;
            default -> MemoryErrorCode.MEMORY_UNAVAILABLE;
        });
    }

    private A2aMemoryInvocation invocation(RunnableConfig config) {
        if (config == null) return null;
        Object candidate = config.metadata(INVOCATION_METADATA_KEY).orElse(null);
        return candidate instanceof A2aMemoryInvocation invocation ? invocation : null;
    }

    private void requireThread(A2aMemoryInvocation invocation, RunnableConfig config) {
        if (config.threadId().isEmpty() || !invocation.taskId().equals(config.threadId().get())) {
            throw new MemoryException(MemoryErrorCode.AUTHENTICATION_FAILED);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Message> messages(OverAllState state) {
        Object raw = state == null ? null : state.value("messages").orElse(List.of());
        if (!(raw instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof Message))) {
            throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT);
        }
        return (List<Message>) list;
    }

    private String stableId(String prefix, Object identity) {
        return prefix + MemoryCanonicalHash.contentHash(identity);
    }

    private String requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw new IllegalArgumentException("invalid A2A memory identity");
        return value;
    }

    @Override public Map<String, KeyStrategy> getKeyStrategys() {
        return Map.of("messages", (oldValue, update) -> update instanceof ReplaceAllWith<?> replacement
                ? replacement.newValues() : new com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy().apply(oldValue, update));
    }
}
