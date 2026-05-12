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
 * Business analysis tool proxied through MCP.
 */
@Component
@AgentBaseTool
public class AgentBusinessTool implements AgentPlannerTool {

    private final AgentMcpClient agentMcpClient;

    public AgentBusinessTool(AgentMcpClient agentMcpClient) {
        this.agentMcpClient = agentMcpClient;
    }

    @Tool(name = "queryMonthlyKpis", description = "Query monthly KPI summaries from the business MCP server.")
    @AgentPlannerAction(
            action = "QUERY_MONTHLY_KPI",
            inputDescription = "Month to query, for example 2026-05.",
            outputDescription = "Monthly KPI summary text.",
            requiredArguments = {"month"},
            exampleArguments = "{\"month\":\"2026-05\"}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"month\":{\"type\":\"string\"}},\"required\":[\"month\"]}",
            outputSchema = "{\"type\":\"object\",\"properties\":{\"resultText\":{\"type\":\"string\"},\"month\":{\"type\":\"string\"},\"kpis\":{\"type\":\"array\"}},\"required\":[\"resultText\",\"month\",\"kpis\"]}",
            exampleOutput = "{\"resultText\":\"Employee Zhang San closed 4 deals and added 8 listings.\",\"month\":\"2026-05\",\"kpis\":[]}",
            executionType = AgentToolExecutionType.MCP_CLIENT,
            mcpServerName = AgentMcpNames.BUSINESS_SERVER,
            mcpToolName = AgentMcpNames.QUERY_MONTHLY_KPIS
    )
    public String queryMonthlyKpis(@AgentPlannerParam("month") String month) {
        AgentMcpCallResult result = agentMcpClient.callTool(
                AgentMcpNames.BUSINESS_SERVER,
                AgentMcpNames.QUERY_MONTHLY_KPIS,
                Map.of("month", month)
        );
        AgentToolContextHolder.recordMcpTool(result.serverName(), result.toolName(), "month=" + month, result.text());
        return result.text();
    }
}
