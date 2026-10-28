package com.bkanent.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowledge.source")
public class KnowledgeSourceProperties {
    private String viewName = "knowledge_interview_public_source_v1";
    private int viewVersion = 1;
    private int supportedSanitizationRevision = 1;
    private int maxContentCharacters = 2_000_000;

    public String getViewName() { return viewName; }
    public void setViewName(String value) { viewName = value; }
    public int getViewVersion() { return viewVersion; }
    public void setViewVersion(int value) { viewVersion = value; }
    public int getSupportedSanitizationRevision() { return supportedSanitizationRevision; }
    public void setSupportedSanitizationRevision(int value) { supportedSanitizationRevision = value; }
    public int getMaxContentCharacters() { return maxContentCharacters; }
    public void setMaxContentCharacters(int value) { maxContentCharacters = value; }

    public void validate() {
        if (!"knowledge_interview_public_source_v1".equals(viewName) || viewVersion != 1
                || supportedSanitizationRevision != 1 || maxContentCharacters < 1
                || maxContentCharacters > 10_000_000) {
            throw new IllegalStateException("Unsupported Knowledge source view or sanitization version");
        }
    }
}
