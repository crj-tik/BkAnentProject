package com.bkanent.agent.tool;

import com.bkanent.agent.planner.annotation.AgentPlannerAction;
import com.bkanent.agent.planner.annotation.AgentPlannerParam;
import com.bkanent.agent.tool.context.AgentToolContextHolder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 最终答复草稿工具。
 */
@Component
public class AgentResponseTool implements AgentPlannerTool {

    @Tool(name = "finalResponseDraft", description = "根据已有结果生成最终答复草稿。")
    @AgentPlannerAction(
            action = "FINAL_RESPONSE",
            inputDescription = "整理后的总结内容。",
            outputDescription = "最终答复草稿文本。",
            requiredArguments = {"summary"},
            exampleArguments = "{\"summary\":\"请结合 KPI 和知识库结果输出建议。\"}",
            inputSchema = "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},\"required\":[\"summary\"]}",
            outputSchema = "{\"type\":\"object\",\"properties\":{\"resultText\":{\"type\":\"string\"},\"summary\":{\"type\":\"string\"}},\"required\":[\"resultText\",\"summary\"]}",
            exampleOutput = "{\"resultText\":\"结合 KPI 与知识库信息，建议优先补足带看转化。\",\"summary\":\"请结合 KPI 和知识库结果输出建议。\"}"
    )
    public String finalResponseDraft(@AgentPlannerParam("summary") String summary) {
        AgentToolContextHolder.recordTool("finalResponseDraft", summary, summary);
        return summary;
    }
}
