package com.bkanent.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 治理面 Agent 配置（对齐 ContractAgentProperties 模式）。
 */
@ConfigurationProperties(prefix = "interview.agent")
public class InterviewAgentProperties {

    private String model = "deepseek-chat";
    private Double temperature = 0.3;
    private Integer maxTokens = 4000;
    private String systemPrompt = "";

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
}
