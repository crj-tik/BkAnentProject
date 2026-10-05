package com.bkanent.agent.orchestration;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.registry.RegisteredAgentDescriptor;
import com.bkanent.agent.service.A2aExecutionService;
import com.bkanent.agent.service.AgentPermissionService;
import com.bkanent.common.agent.AgentTaskInvokeRequest;
import com.bkanent.common.agent.CapabilityId;
import com.bkanent.common.agent.SkillPublication;
import com.bkanent.common.agent.SkillSelection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@Component
public class SupervisorCapabilityCatalog {
    private static final String A2A_SCHEMA = """
            {"type":"object","properties":{"instruction":{"type":"string","minLength":1},
             "context":{"type":"object"},"skill":{"type":"object","description":"可选的下游本地技能，只使用此 Agent Card 发布映射中的 name/version；不是能力 ID 或父技能。未明确需要时省略。","properties":{
              "name":{"type":"string"},"version":{"type":"string","description":"仅填已发布版本，不确定时省略"}},"required":["name"],"additionalProperties":false}},
             "required":["instruction"],"additionalProperties":false}
            """;
    private final AgentRegistry agents;
    private final AgentMcpClient mcp;
    private final AgentPermissionService permissions;
    private final A2aExecutionService execution;
    private final ToolCallbackProvider local;
    private final ObjectMapper mapper;

    public SupervisorCapabilityCatalog(AgentRegistry agents, AgentMcpClient mcp, AgentPermissionService permissions,
                                       A2aExecutionService execution,
                                       @Qualifier("localToolCallbackProvider") ToolCallbackProvider local, ObjectMapper mapper) {
        this.agents = agents; this.mcp = mcp; this.permissions = permissions;
        this.execution = execution; this.local = local; this.mapper = mapper;
    }

    public Map<String, SupervisorCapability> snapshot(String userId, boolean allowMcp) {
        Map<String, SupervisorCapability> result = new LinkedHashMap<>();
        for (RegisteredAgentDescriptor descriptor : agents.listDescriptors()) {
            if (descriptor.agentCard() == null || !permissions.canInvokeChildAgent(userId, descriptor)) continue;
            String id = CapabilityId.a2a(descriptor.agentId()).value();
            var card = descriptor.agentCard();
            String description = card.description() + "\nTarget: " + descriptor.agentId() + "\nSkills: " + card.skillDescriptors()
                    + "\nExplicit skill publication: " + card.capabilities().getOrDefault("extensions", List.of());
            var callback = callback(id, description, A2A_SCHEMA, (arguments, context) ->
                    invokeAgent(descriptor.agentId(), arguments, context));
            add(result, new SupervisorCapability(id, "a2a", descriptor.agentId(), card.version(), callback));
        }
        if (allowMcp && permissions.canUseMcpTools(userId)) for (var tool : mcp.listTools()) {
            if (!permissions.canReadMcpServer(userId, tool.serverName())) continue;
            String id = CapabilityId.mcp(tool.serverName(), tool.toolName()).value();
            add(result, new SupervisorCapability(id, "mcp", tool.serverName(), tool.inputSchema(),
                    callback(id, tool.description() == null ? tool.toolName() : tool.description(), tool.inputSchema(),
                            (arguments, context) -> {
                                String actor = required(context, "userId");
                                if (!permissions.canUseMcpTools(actor) || !permissions.canReadMcpServer(actor, tool.serverName())) throw new IllegalStateException("CAPABILITY_PERMISSION_DENIED");
                                return json(mcp.callTool(tool.serverName(), tool.toolName(), arguments));
                            })));
        }
        if (permissions.canUseLocalTools(userId)) for (ToolCallback tool : local.getToolCallbacks()) {
            String id = CapabilityId.local(tool.getToolDefinition().name()).value();
            ToolCallback aliased = new ToolCallback() {
                public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name(alias(id))
                        .description(tool.getToolDefinition().description()).inputSchema(tool.getToolDefinition().inputSchema()).build(); }
                public String call(String input) { throw new IllegalStateException("authenticated tool context required"); }
                public String call(String input, ToolContext context) {
                    permissions.assertPermission(required(context.getContext(), "userId"), "agent.chat.use", "local tool invocation");
                    return tool.call(input, context);
                }
            };
            add(result, new SupervisorCapability(id, "local", tool.getToolDefinition().name(), "1", aliased));
        }
        return Map.copyOf(result);
    }

    private String invokeAgent(String agentId, Map<String, Object> arguments, Map<String, Object> context) {
        var descriptor = agents.getByAgentId(agentId).orElseThrow(() -> new IllegalStateException("CAPABILITY_UNAVAILABLE"));
        String actor = required(context, "userId");
        if (!permissions.canInvokeChildAgent(actor, descriptor)) throw new IllegalStateException("CAPABILITY_PERMISSION_DENIED");
        String runId = required(context, "runId"), callId = required(context, "callId");
        Map<String, Object> structured = new LinkedHashMap<>();
        if (arguments.get("context") instanceof Map<?, ?> raw) raw.forEach((key, value) -> structured.put(String.valueOf(key), value));
        structured.put("userId", actor);
        structured.put("parentSkill", context.getOrDefault("parentSkill", Map.of()));
        SkillSelection skill = resolveChildSkill(descriptor, arguments.get("skill"));
        String childId = "call-" + digest(runId + "\u0000" + callId);
        var request = new AgentTaskInvokeRequest(required(context, "sessionId"), childId, runId,
                required(context, "traceId"), "supervisor-agent", agentId, null, null,
                (String) arguments.get("instruction"), structured, List.of(), List.of(), "json", callId,
                Boolean.TRUE.equals(context.get("stream")), skill);
        Map<String, Object> metadata = Map.of("capabilityId", CapabilityId.a2a(agentId).value(), "callId", callId, "parentRunId", runId);
        if (context.get("acceptedTaskRecorder") instanceof java.util.function.Consumer<?> recorder) {
            @SuppressWarnings("unchecked") var accepted = (java.util.function.Consumer<com.bkanent.agent.client.AcceptedA2aTask>) recorder;
            return json(execution.execute(descriptor, request, "tool", metadata, accepted));
        }
        return json(execution.execute(descriptor, request, "tool", metadata));
    }

    /** Pure target-contract validation, before approval or claiming a potentially effectful call. */
    public void validateArguments(SupervisorCapability capability, Map<String, Object> arguments) {
        if (!"a2a".equals(capability.protocol())) return;
        var descriptor = agents.getByAgentId(capability.target()).orElseThrow(() -> new IllegalStateException("CAPABILITY_UNAVAILABLE"));
        resolveChildSkill(descriptor, arguments.get("skill"));
    }

    private SkillSelection resolveChildSkill(RegisteredAgentDescriptor descriptor, Object selected) {
        if (selected == null) return null;
        if (!(selected instanceof Map<?, ?> fields) || !(fields.get("name") instanceof String name)) throw new IllegalArgumentException("SKILL_POLICY_INVALID");
        Object extensions = descriptor.agentCard().capabilities().get("extensions");
        if (!(extensions instanceof List<?> entries)) throw new IllegalStateException("EXPLICIT_SKILL_UNSUPPORTED");
        for (Object entry : entries) {
            Map<?, ?> extension = mapper.convertValue(entry, Map.class);
            if (!SkillPublication.EXTENSION_URI.equals(extension.get("uri"))) continue;
            if (!(extension.get("params") instanceof Map<?, ?> params) || !"1".equals(params.get("contractVersion"))) continue;
            if (params.get("skills") instanceof List<?> publications) for (Object item : publications) {
                var publication = mapper.convertValue(item, SkillPublication.class);
                if (!publication.name().equals(name)) continue;
                if (fields.get("version") != null && !publication.version().equals(fields.get("version"))) throw new IllegalStateException("SKILL_VERSION_MISMATCH");
                if (descriptor.agentCard().skillDescriptors().stream().noneMatch(s -> publication.cardSkillId().equals(s.id()))) throw new IllegalStateException("SKILL_POLICY_INVALID");
                return publication.selection();
            }
            throw new IllegalArgumentException("SKILL_NOT_FOUND");
        }
        throw new IllegalStateException("EXPLICIT_SKILL_UNSUPPORTED");
    }

    private ToolCallback callback(String id, String description, String schema, BiFunction<Map<String, Object>, Map<String, Object>, String> function) {
        var definition = ToolDefinition.builder().name(alias(id)).description(description).inputSchema(schema).build();
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return definition; }
            public String call(String input) { throw new IllegalStateException("authenticated tool context required"); }
            public String call(String input, ToolContext context) {
                try { return function.apply(mapper.readValue(input, new TypeReference<>() {}), context.getContext()); }
                catch (RuntimeException exception) { throw exception; }
                catch (Exception exception) { throw new IllegalArgumentException("TOOL_ARGUMENTS_INVALID", exception); }
            }
        };
    }

    private void add(Map<String, SupervisorCapability> result, SupervisorCapability capability) {
        if (result.putIfAbsent(capability.capabilityId(), capability) != null) throw new IllegalStateException("duplicate capability: " + capability.capabilityId());
    }
    public static String alias(String id) { return id.substring(0, id.indexOf(':')) + "_" + digest(id).substring(0, 24); }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private String required(Map<String, Object> context, String name) {
        if (!(context.get(name) instanceof String value) || value.isBlank()) throw new IllegalStateException("missing authenticated " + name);
        return value;
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
