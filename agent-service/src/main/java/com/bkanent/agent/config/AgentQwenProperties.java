package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.qwen")
public class AgentQwenProperties {

    private String model = "qwen-plus";
    private Double temperature = 0.2D;
    private Integer maxTokens = 1200;
    private Integer decisionMaxTokens = 320;
    private Integer defaultTopK = 4;

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

    public Integer getDecisionMaxTokens() {
        return decisionMaxTokens;
    }

    public void setDecisionMaxTokens(Integer decisionMaxTokens) {
        this.decisionMaxTokens = decisionMaxTokens;
    }

    public Integer getDefaultTopK() {
        return defaultTopK;
    }

    public void setDefaultTopK(Integer defaultTopK) {
        this.defaultTopK = defaultTopK;
    }
}
