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
 * Listing compare tool proxied through MCP.
 */
@Component
@AgentBaseTool
public class AgentCompareTool implements AgentPlannerTool {

    private final AgentMcpClient agentMcpClient;

    public AgentCompareTool(AgentMcpClient agentMcpClient) {
        this.agentMcpClient = agentMcpClient;
    }

    @Tool(name = "compareListings", description = "Compare listings through the compare MCP server.")
    @AgentPlannerAction(
            action = "COMPARE_LISTINGS",
            inputDescription = "Comma-separated listing ids.",
            outputDescription = "Listing comparison result text.",
            requiredArguments = {"listingIds"},
            exampleArguments = "{\"listingIds\":\"1,2,3\"}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"listingIds\":{\"type\":\"string\"}},\"required\":[\"listingIds\"]}",
            outputSchema = "{\"type\":\"object\",\"properties\":{\"resultText\":{\"type\":\"string\"},\"listingIds\":{\"type\":\"string\"},\"comparisonTableMarkdown\":{\"type\":\"string\"},\"aiConclusion\":{\"type\":\"string\"}},\"required\":[\"resultText\",\"listingIds\",\"comparisonTableMarkdown\",\"aiConclusion\"]}",
            exampleOutput = "{\"resultText\":\"Comparison finished.\",\"listingIds\":\"1,2,3\",\"comparisonTableMarkdown\":\"| listing | result |\",\"aiConclusion\":\"Listing 2 is more cost effective.\"}",
            executionType = AgentToolExecutionType.MCP_CLIENT,
            mcpServerName = AgentMcpNames.COMPARE_SERVER,
            mcpToolName = AgentMcpNames.COMPARE_LISTINGS
    )
    public String compareListings(@AgentPlannerParam("listingIds") String listingIdsText) {
        AgentMcpCallResult result = agentMcpClient.callTool(
                AgentMcpNames.COMPARE_SERVER,
                AgentMcpNames.COMPARE_LISTINGS,
                Map.of("listingIds", listingIdsText)
        );
        AgentToolContextHolder.recordMcpTool(result.serverName(), result.toolName(), "listingIds=" + listingIdsText, result.text());
        return result.text();
    }
}
