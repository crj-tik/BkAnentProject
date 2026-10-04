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
    private final SupervisorCapabilityCatalog catalog = new SupervisorCapabilityCatalog(agents, mcp, permissions,
            mock(A2aExecutionService.class), ToolCallbackProvider.from(local), new ObjectMapper());

    @Test
    void identicalMcpAndLocalNamesKeepAllRealBindingsAndDescriptions() {
        when(agents.listDescriptors()).thenReturn(List.of());
        when(permissions.canUseLocalTools("1")).thenReturn(true);
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
        assertThat(catalog.snapshot("1", true)).containsOnlyKeys("mcp:added:search");
        when(mcp.listTools()).thenReturn(List.of());
        assertThat(catalog.snapshot("1", true)).isEmpty();
    }

    private AgentMcpToolDescriptor tool(String server) {
        return new AgentMcpToolDescriptor(server, "search", server + " real description", "{\"type\":\"object\"}", null);
    }
}
