package com.bkanent.agent.service.impl;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.model.AgentMcpToolDescriptor;
import com.bkanent.agent.mcp.model.AgentToolCatalogItem;
import com.bkanent.agent.service.AgentToolCatalogService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AgentToolCatalogServiceImpl 服务实现类。
 */
@Service
public class AgentToolCatalogServiceImpl implements AgentToolCatalogService {

    /**
     * 字段：agentMcpClient。
     */
    private final AgentMcpClient agentMcpClient;

    /**
     * 处理AgentToolCatalogServiceImpl。
     */
    public AgentToolCatalogServiceImpl(AgentMcpClient agentMcpClient) {
        this.agentMcpClient = agentMcpClient;
    }

    /**
     * 查询catalog。
     */
    @Override
    public List<AgentToolCatalogItem> listCatalog() {
        return agentMcpClient.listTools().stream()
                .map(this::toCatalogItem)
                .toList();
    }

    /**
     * 转换catalogItem。
     */
    private AgentToolCatalogItem toCatalogItem(AgentMcpToolDescriptor tool) {
        return new AgentToolCatalogItem(
                tool.serverName(),
                tool.toolName(),
                tool.description(),
                tool.inputSchema(),
                tool.outputSchema(),
                true,
                true,
                tool.toolName(),
                "SPRING_AI_SYNC_MCP_TOOL_CALLBACK",
                null
        );
    }
}
