package com.bkanent.agent.config;

import com.bkanent.agent.tool.AgentPlannerTool;
import com.bkanent.agent.tool.annotation.AgentBaseTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 基础工具配置类。
 *
 * <p>该配置会扫描所有 {@link AgentPlannerTool} 实现，只把声明为基础工具的实现类封装成
 * {@link ToolCallbackProvider}，供 ChatClient 初始化默认工具时复用。</p>
 */
@Configuration
public class BaseToolsConfig {

    @Bean("baseToolCallbackProvider")
    public ToolCallbackProvider baseToolCallbackProvider(List<AgentPlannerTool> plannerTools) {
        List<Object> baseTools = plannerTools.stream()
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

    private boolean isBaseTool(AgentPlannerTool tool) {
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
