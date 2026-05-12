package com.bkanent.agent.tool;

import com.bkanent.agent.mcp.AgentMcpClient;
import com.bkanent.agent.mcp.AgentMcpNames;
import com.bkanent.agent.mcp.AgentToolExecutionType;
import com.bkanent.agent.mcp.model.AgentMcpCallResult;
import com.bkanent.agent.planner.annotation.AgentPlannerAction;
import com.bkanent.agent.planner.annotation.AgentPlannerParam;
import com.bkanent.agent.tool.annotation.AgentBaseTool;
import com.bkanent.agent.tool.context.AgentToolContextHolder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Marketing publish tool proxied through MCP.
 */
@Component
@AgentBaseTool
public class AgentMarketingPublishTool implements AgentPlannerTool {

    private final AgentMcpClient agentMcpClient;

    public AgentMarketingPublishTool(AgentMcpClient agentMcpClient) {
        this.agentMcpClient = agentMcpClient;
    }

    @Tool(name = "publishMarketingContent", description = "Publish marketing content through the marketing MCP server.")
    @AgentPlannerAction(
            action = "PUBLISH_MARKETING_CONTENT",
            inputDescription = "Listing id, copywriting and target platforms.",
            outputDescription = "Marketing publishing result text.",
            requiredArguments = {"listingId", "platforms", "copywriting"},
            exampleArguments = "{\"listingId\":1,\"platforms\":\"douyin,xiaohongshu\",\"copywriting\":\"Near subway and school district.\"}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"listingId\":{\"type\":\"integer\"},\"platforms\":{\"type\":\"string\"},\"copywriting\":{\"type\":\"string\"}},\"required\":[\"listingId\",\"platforms\",\"copywriting\"]}",
            outputSchema = "{\"type\":\"object\",\"properties\":{\"resultText\":{\"type\":\"string\"},\"listingId\":{\"type\":\"integer\"},\"platforms\":{\"type\":\"string\"},\"contents\":{\"type\":\"array\"}},\"required\":[\"resultText\",\"listingId\",\"platforms\",\"contents\"]}",
            exampleOutput = "{\"resultText\":\"Generated and published 2 contents.\",\"listingId\":1,\"platforms\":\"douyin,xiaohongshu\",\"contents\":[]}",
            executionType = AgentToolExecutionType.MCP_CLIENT,
            mcpServerName = AgentMcpNames.MARKETING_SERVER,
            mcpToolName = AgentMcpNames.PUBLISH_MARKETING_CONTENT
    )
    public String publishMarketingContent(@AgentPlannerParam("listingId") Long listingId,
                                          @AgentPlannerParam("copywriting") String copywriting,
                                          @AgentPlannerParam("platforms") String platforms) {
        AgentMcpCallResult result = agentMcpClient.callTool(
                AgentMcpNames.MARKETING_SERVER,
                AgentMcpNames.PUBLISH_MARKETING_CONTENT,
                Map.of(
                        "listingId", listingId,
                        "copywriting", copywriting,
                        "platforms", platforms
                )
        );
        AgentToolContextHolder.recordMcpTool(
                result.serverName(),
                result.toolName(),
                "listingId=" + listingId + ", platforms=" + platforms,
                result.text()
        );
        return result.text();
    }
}
