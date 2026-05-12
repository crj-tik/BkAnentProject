package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent Milvus 使用配置。
 */
@ConfigurationProperties(prefix = "agent.milvus")
public class AgentMilvusProperties {

    /**
     * 业务属性：defaultCollection。
     */
    private String defaultCollection = "agent_knowledge";
    /**
     * 业务属性：primaryField。
     */
    private String primaryField = "doc_id";
    /**
     * 业务属性：vectorField。
     */
    private String vectorField = "embedding";
    /**
     * 业务属性：textField。
     */
    private String textField = "content";
    /**
     * 业务属性：sourceTypeField。
     */
    private String sourceTypeField = "source_type";
    /**
     * 业务属性：sourceIdField。
     */
    private String sourceIdField = "source_id";
    /**
     * 业务属性：metricType。
     */
    private String metricType = "COSINE";
    /**
     * 业务属性：maxTextLength。
     */
    private Integer maxTextLength = 8192;

    public String getDefaultCollection() {
        return defaultCollection;
    }

    public void setDefaultCollection(String defaultCollection) {
        this.defaultCollection = defaultCollection;
    }

    public String getPrimaryField() {
        return primaryField;
    }

    public void setPrimaryField(String primaryField) {
        this.primaryField = primaryField;
    }

    public String getVectorField() {
        return vectorField;
    }

    public void setVectorField(String vectorField) {
        this.vectorField = vectorField;
    }

    public String getTextField() {
        return textField;
    }

    public void setTextField(String textField) {
        this.textField = textField;
    }

    public String getSourceTypeField() {
        return sourceTypeField;
    }

    public void setSourceTypeField(String sourceTypeField) {
        this.sourceTypeField = sourceTypeField;
    }

    public String getSourceIdField() {
        return sourceIdField;
    }

    public void setSourceIdField(String sourceIdField) {
        this.sourceIdField = sourceIdField;
    }

    public String getMetricType() {
        return metricType;
    }

    public void setMetricType(String metricType) {
        this.metricType = metricType;
    }

    public Integer getMaxTextLength() {
        return maxTextLength;
    }

    public void setMaxTextLength(Integer maxTextLength) {
        this.maxTextLength = maxTextLength;
    }
}
