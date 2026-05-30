package com.bkanent.agent.config;

import com.bkanent.agent.tool.AgentTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class BaseToolsConfig {

    @Bean("localToolCallbackProvider")
    public ToolCallbackProvider localToolCallbackProvider(List<AgentTool> agentTools) {
        if (agentTools.isEmpty()) {
            return ToolCallbackProvider.from();
        }
        return MethodToolCallbackProvider.builder()
                .toolObjects(agentTools.toArray())
                .build();
    }
}
