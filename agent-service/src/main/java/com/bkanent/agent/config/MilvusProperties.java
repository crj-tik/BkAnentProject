package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {

    private boolean enabled;
    private String endpoint;
    private String token;
    private String database;
    private String defaultCollection;
    private String primaryField = "doc_id";
    private String vectorField = "embedding";
    private String textField = "content";
    private String sourceTypeField = "source_type";
    private String sourceIdField = "source_id";
    private String metricType = "COSINE";
    private Integer maxTextLength = 8192;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

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
