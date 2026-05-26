package com.bkanent.agent.config;

import com.bkanent.agent.tool.AgentTool;
import com.bkanent.agent.tool.annotation.AgentBaseTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * BaseToolsConfig 配置类。
 */
@Configuration
public class BaseToolsConfig {

    /**
     * 处理localBaseToolCallbackProvider。
     */
    @Bean("localBaseToolCallbackProvider")
    public ToolCallbackProvider localBaseToolCallbackProvider(List<AgentTool> agentTools) {
        List<Object> baseTools = agentTools.stream()
                .filter(this::isBaseTool)
                .map(tool -> (Object) tool)
                .toList();
        if (baseTools.isEmpty()) {
            return ToolCallbackProvider.from();
        }
        return MethodToolCallbackProvider.builder()
                .toolObjects(baseTools.toArray())
                .build();
    }

    /**
     * 判断是否baseTool。
     */
    private boolean isBaseTool(AgentTool tool) {
        if (tool.isBaseTool()) {
            return true;
        }
        Class<?> targetClass = AopUtils.getTargetClass(tool);
        if (targetClass == null) {
            targetClass = tool.getClass();
        }
        return targetClass.isAnnotationPresent(AgentBaseTool.class);
    }
}
