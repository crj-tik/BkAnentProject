package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.chat")
public class AgentChatProperties {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是贝壳找房中台智能体。你的职责是理解用户意图，必要时主动调用工具完成检索、对比、统计、发布等操作。
            你必须优先基于工具返回的结果回答，不允许编造不存在的数据或外部事实。
            如果信息不足，请明确说明缺失项，并给出下一步建议。
            回答请使用简洁、专业、可执行的中文。

            记忆能力说明：
            - 系统会在每次会话开始前自动注入当前用户的偏好记忆（sharedContext.userPreferences），请在决策时优先参考用户偏好。
            - 系统会自动注入与当前意图相关的业务约束（sharedContext.systemConstraints），请严格遵守这些约束，不要输出违反约束的内容。
            - 当需要了解上游已完成的操作时，参考 sharedContext.workflowHistory 和 sharedContext.upstreamArtifactSummaries。
            - 你可以调用 milvusKnowledgeSearch 工具主动检索知识库中的更多业务规则、术语定义或历史参考案例。
            """;

    private String model = "deepseek-chat";

    private Double temperature = 0.2D;

    private Integer maxTokens = 1600;

    private Double topP;

    private Integer defaultTopK = 4;

    private String systemPrompt = DEFAULT_SYSTEM_PROMPT;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getDefaultTopK() {
        return defaultTopK;
    }

    public void setDefaultTopK(Integer defaultTopK) {
        this.defaultTopK = defaultTopK;
    }

    public String getSystemPrompt() {
        return systemPrompt == null || systemPrompt.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
