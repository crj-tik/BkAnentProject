package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent DeepSeek configuration.
 */
@ConfigurationProperties(prefix = "agent.deepseek")
public class AgentDeepSeekProperties {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是房产中台智能体。你的职责是理解用户意图，必要时主动调用工具完成检索、对比、统计、发布等操作。
            你必须优先基于工具返回的结果回答，不允许编造不存在的数据或外部事实。
            如果信息不足，请明确说明缺失项，并给出下一步建议。
            回答请使用简洁、专业、可执行的中文。
            """;

    private String model = "deepseek-chat";

    private Double temperature = 0.2D;

    private Integer maxTokens = 1600;

    private Double topP;

    private Integer defaultTopK = 4;

    private Integer plannerMaxReplanCount = 2;

    private Integer plannerFixRetryCount = 1;

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

    public Integer getPlannerMaxReplanCount() {
        return plannerMaxReplanCount;
    }

    public void setPlannerMaxReplanCount(Integer plannerMaxReplanCount) {
        this.plannerMaxReplanCount = plannerMaxReplanCount;
    }

    public Integer getPlannerFixRetryCount() {
        return plannerFixRetryCount;
    }

    public void setPlannerFixRetryCount(Integer plannerFixRetryCount) {
        this.plannerFixRetryCount = plannerFixRetryCount;
    }

    public String getSystemPrompt() {
        return systemPrompt == null || systemPrompt.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
