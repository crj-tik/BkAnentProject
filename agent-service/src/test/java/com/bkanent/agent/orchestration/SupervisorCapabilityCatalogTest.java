package com.bkanent.agent.orchestration;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.model.AgentMcpToolDescriptor;
import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.registry.AgentRegistry;
import com.bkanent.agent.service.A2aExecutionService;
import com.bkanent.agent.service.AgentPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupervisorCapabilityCatalogTest {
    private final AgentRegistry agents = mock(AgentRegistry.class);
    private final AgentMcpClient mcp = mock(AgentMcpClient.class);
    private final AgentPermissionService permissions = mock(AgentPermissionService.class);
    private final AtomicInteger effects = new AtomicInteger();
    private final ToolCallback local = new ToolCallback() {
        public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("search").description("real local search")
                .inputSchema("{\"type\":\"object\"}").build(); }
        public String call(String input) { effects.incrementAndGet(); return "local result"; }
    };
    private final A2aExecutionService execution = mock(A2aExecutionService.class);
    private final SupervisorCapabilityCatalog catalog = new SupervisorCapabilityCatalog(agents, mcp, permissions,
            execution, ToolCallbackProvider.from(local), new ObjectMapper());

    @Test
    void a2aBindingUsesStableChildIdentityPublishedSelectionAndAuthenticatedParentContext() {
        var publication = Map.of("cardSkillId", "bk-skill/listing/listing-search", "name", "listing-search", "owner", "listing", "version", "1", "contentHash", "hash");
        var card = new com.bkanent.common.agent.AgentCard("listing-agent", "listing", "actual description", "1", List.of(), List.of("listing"), false, true,
                "http://actual/a2a", List.of("text"), List.of("application/json"),
                List.of(new com.bkanent.common.agent.AgentSkillDescriptor("bk-skill/listing/listing-search", "listing-search", "find", List.of(), List.of(), List.of(), List.of())),
                Map.of("extensions", List.of(Map.of("uri", com.bkanent.common.agent.SkillPublication.EXTENSION_URI,
                        "params", Map.of("contractVersion", "1", "owner", "listing", "skills", List.of(publication))))), "JSONRPC", "0.2.5");
        var descriptor = new com.bkanent.agent.registry.RegisteredAgentDescriptor("listing-agent", "http://actual", "/.well-known/agent.json", "/a2a",
                com.bkanent.agent.registry.AgentRuntimeType.ALIBABA_A2A, com.bkanent.agent.registry.AgentDescriptorSource.DISCOVERED_CARD, card, Map.of());
        when(agents.listDescriptors()).thenReturn(List.of(descriptor)); when(agents.getByAgentId("listing-agent")).thenReturn(java.util.Optional.of(descriptor));
        when(permissions.canInvokeChildAgent("1", descriptor)).thenReturn(true);
        when(execution.execute(eq(descriptor), any(), eq("tool"), anyMap())).thenReturn(new com.bkanent.common.agent.AgentTaskInvokeResponse(
                "session", "child", "listing-agent", "COMPLETED", Map.of("listingIds", List.of(101)), List.of("artifact"), List.of(), "actual", "trace"));
        var callback = catalog.snapshot("1", false).get("a2a:listing-agent").callback();
        var binding = catalog.snapshot("1", false).get("a2a:listing-agent");
        assertThatThrownBy(() -> catalog.validateArguments(binding, Map.of("skill", Map.of("name", "a2a:listing-agent"))))
                .hasMessage("SKILL_NOT_FOUND");
        assertThatThrownBy(() -> catalog.validateArguments(binding, Map.of("skill", Map.of("name", "listing-search", "version", "2"))))
                .hasMessage("SKILL_VERSION_MISMATCH");
        verifyNoInteractions(execution);
        var context = new ToolContext(Map.of("userId", "1", "runId", "run", "callId", "first", "sessionId", "session", "traceId", "trace", "parentSkill", Map.of("name", "parent")));
        assertThat(callback.call("{\"instruction\":\"find\",\"context\":{\"userId\":\"2\",\"parentSkill\":\"forged\"}}", context)).contains("listingIds", "artifact");
        var capture = org.mockito.ArgumentCaptor.forClass(com.bkanent.common.agent.AgentTaskInvokeRequest.class);
        verify(execution).execute(eq(descriptor), capture.capture(), eq("tool"), anyMap());
        var original = capture.getValue();
        assertThat(original.structuredContext()).containsEntry("userId", "1").containsEntry("parentSkill", Map.of("name", "parent"));
        assertThat(original.skillSelection()).isNull(); assertThat(original.parentTaskId()).isEqualTo("run");
        clearInvocations(execution);
        callback.call("{\"instruction\":\"find\",\"skill\":{\"name\":\"listing-search\"}}", context);
        verify(execution).execute(eq(descriptor), capture.capture(), eq("tool"), anyMap());
        assertThat(capture.getValue().taskId()).isEqualTo(original.taskId());
        assertThat(capture.getValue().skillSelection()).isEqualTo(new com.bkanent.common.agent.SkillSelection("listing-search", "1", "hash", "listing"));
        clearInvocations(execution);
        callback.call("{\"instruction\":\"another actual call\"}", new ToolContext(Map.of("userId", "1", "runId", "run", "callId", "second", "sessionId", "session", "traceId", "trace")));
        verify(execution).execute(eq(descriptor), capture.capture(), eq("tool"), anyMap());
        assertThat(capture.getValue().taskId()).isNotEqualTo(original.taskId());
        assertThatThrownBy(() -> callback.call("{\"instruction\":\"find\",\"skill\":{\"name\":\"listing-search\",\"version\":\"2\"}}", context)).hasMessage("SKILL_VERSION_MISMATCH");
    }

    @Test
    void identicalMcpAndLocalNamesKeepAllRealBindingsAndDescriptions() {
        when(agents.listDescriptors()).thenReturn(List.of());
        when(permissions.canUseLocalTools("1")).thenReturn(true);
        when(permissions.canUseMcpTools("1")).thenReturn(true);
        when(permissions.canReadMcpServer(eq("1"), anyString())).thenReturn(true);
        when(mcp.listTools()).thenReturn(List.of(tool("one"), tool("two")));
        when(mcp.callTool("two", "search", Map.of("query", "real")))
                .thenReturn(new AgentMcpCallResult("two", "search", "remote result", Map.of()));
        var snapshot = catalog.snapshot("1", true);
        assertThat(snapshot).containsOnlyKeys("local:search", "mcp:one:search", "mcp:two:search");
        assertThat(snapshot.values().stream().map(SupervisorCapability::toolName).distinct()).hasSize(3);
        assertThat(snapshot.get("mcp:two:search").callback().getToolDefinition().description()).isEqualTo("two real description");
        assertThat(snapshot.get("mcp:two:search").callback().call("{\"query\":\"real\"}", new ToolContext(Map.of("userId", "1"))))
                .contains("remote result");
        verify(mcp).callTool("two", "search", Map.of("query", "real"));
        assertThat(effects).hasValue(0);
        when(permissions.canReadMcpServer("1", "two")).thenReturn(false);
        assertThatThrownBy(() -> snapshot.get("mcp:two:search").callback().call("{}", new ToolContext(Map.of("userId", "1"))))
                .hasMessageContaining("PERMISSION_DENIED");
        verify(mcp, times(1)).callTool(anyString(), anyString(), anyMap());
    }

    @Test
    void coldStartHasNoFakeAgentAndMcpDisableIsRequestHardLimit() {
        when(agents.listDescriptors()).thenReturn(List.of());
        assertThat(catalog.snapshot("1", false)).isEmpty();
        verify(mcp, never()).listTools();
        when(permissions.canReadMcpServer(eq("1"), anyString())).thenReturn(true);
        when(mcp.listTools()).thenReturn(List.of(tool("added")));
        when(permissions.canUseMcpTools("1")).thenReturn(true);
        assertThat(catalog.snapshot("1", true)).containsOnlyKeys("mcp:added:search");
        when(mcp.listTools()).thenReturn(List.of());
        assertThat(catalog.snapshot("1", true)).isEmpty();
    }

    private AgentMcpToolDescriptor tool(String server) {
        return new AgentMcpToolDescriptor(server, "search", server + " real description", "{\"type\":\"object\"}", null);
    }
}
