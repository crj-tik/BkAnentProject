package com.bkanent.agent.planner.schema;

import com.bkanent.agent.planner.definition.AgentPlanActionDefinition;
import com.bkanent.agent.planner.registry.AgentPlanActionRegistry;
import org.springframework.stereotype.Service;

/**
 * Planner Schema 说明服务实现。
 *
 * <p>负责把系统内已注册的动作定义拼装成给大模型使用的 Schema 提示文本。</p>
 */
@Service
public class AgentPlannerSchemaServiceImpl implements AgentPlannerSchemaService {

    private final AgentPlanActionRegistry agentPlanActionRegistry;

    public AgentPlannerSchemaServiceImpl(AgentPlanActionRegistry agentPlanActionRegistry) {
        this.agentPlanActionRegistry = agentPlanActionRegistry;
    }

    @Override
    public String buildActionSchemaPrompt() {
        StringBuilder builder = new StringBuilder();
        for (AgentPlanActionDefinition definition : agentPlanActionRegistry.listDefinitions()) {
            builder.append("- action: ").append(definition.action()).append(System.lineSeparator())
                    .append("  description: ").append(definition.description()).append(System.lineSeparator())
                    .append("  inputDescription: ").append(definition.inputDescription()).append(System.lineSeparator())
                    .append("  outputDescription: ").append(definition.outputDescription()).append(System.lineSeparator())
                    .append("  requiredArguments: ").append(definition.requiredArguments()).append(System.lineSeparator())
                    .append("  exampleArguments: ").append(definition.exampleArguments()).append(System.lineSeparator())
                    .append("  inputSchema: ").append(definition.inputSchema()).append(System.lineSeparator())
                    .append("  outputSchema: ").append(definition.outputSchema()).append(System.lineSeparator())
                    .append("  exampleOutput: ").append(definition.exampleOutput()).append(System.lineSeparator())
                    .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }
}
