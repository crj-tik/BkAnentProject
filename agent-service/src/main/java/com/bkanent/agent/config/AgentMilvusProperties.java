package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AgentMilvusProperties 配置属性类。
 */
@ConfigurationProperties(prefix = "agent.milvus")
public class AgentMilvusProperties {

    /**
     * 字段：defaultCollection。
     */
    private String defaultCollection = "agent_knowledge";

    /**
     * 字段：listingCollection。
     */
    private String listingCollection = "listing_knowledge";

    /**
     * 字段：primaryField。
     */
    private String primaryField = "doc_id";

    /**
     * 字段：vectorField。
     */
    private String vectorField = "embedding";

    /**
     * 字段：textField。
     */
    private String textField = "content";

    /**
     * 字段：sourceTypeField。
     */
    private String sourceTypeField = "source_type";

    /**
     * 字段：sourceIdField。
     */
    private String sourceIdField = "source_id";

    /**
     * 字段：metadataField。
     */
    private String metadataField = "metadata";

    /**
     * 字段：metricType。
     */
    private String metricType = "COSINE";

    /**
     * 字段：indexType。
     */
    private String indexType = "IVF_FLAT";

    /**
     * 字段：indexParameters。
     */
    private String indexParameters = "{\"nlist\":1024}";

    /**
     * 字段：embeddingDimension。
     */
    private Integer embeddingDimension = 1024;

    /**
     * 字段：maxTextLength。
     */
    private Integer maxTextLength = 8192;

    /**
     * 获取defaultCollection。
     */
    public String getDefaultCollection() {
        return defaultCollection;
    }

    /**
     * 设置defaultCollection。
     */
    public void setDefaultCollection(String defaultCollection) {
        this.defaultCollection = defaultCollection;
    }

    /**
     * 获取listingCollection。
     */
    public String getListingCollection() {
        return listingCollection;
    }

    /**
     * 设置listingCollection。
     */
    public void setListingCollection(String listingCollection) {
        this.listingCollection = listingCollection;
    }

    /**
     * 获取primaryField。
     */
    public String getPrimaryField() {
        return primaryField;
    }

    /**
     * 设置primaryField。
     */
    public void setPrimaryField(String primaryField) {
        this.primaryField = primaryField;
    }

    /**
     * 获取vectorField。
     */
    public String getVectorField() {
        return vectorField;
    }

    /**
     * 设置vectorField。
     */
    public void setVectorField(String vectorField) {
        this.vectorField = vectorField;
    }

    /**
     * 获取textField。
     */
    public String getTextField() {
        return textField;
    }

    /**
     * 设置textField。
     */
    public void setTextField(String textField) {
        this.textField = textField;
    }

    /**
     * 获取sourceTypeField。
     */
    public String getSourceTypeField() {
        return sourceTypeField;
    }

    /**
     * 设置sourceTypeField。
     */
    public void setSourceTypeField(String sourceTypeField) {
        this.sourceTypeField = sourceTypeField;
    }

    /**
     * 获取sourceIdField。
     */
    public String getSourceIdField() {
        return sourceIdField;
    }

    /**
     * 设置sourceIdField。
     */
    public void setSourceIdField(String sourceIdField) {
        this.sourceIdField = sourceIdField;
    }

    /**
     * 获取metadataField。
     */
    public String getMetadataField() {
        return metadataField;
    }

    /**
     * 设置metadataField。
     */
    public void setMetadataField(String metadataField) {
        this.metadataField = metadataField;
    }

    /**
     * 获取metricType。
     */
    public String getMetricType() {
        return metricType;
    }

    /**
     * 设置metricType。
     */
    public void setMetricType(String metricType) {
        this.metricType = metricType;
    }

    /**
     * 获取indexType。
     */
    public String getIndexType() {
        return indexType;
    }

    /**
     * 设置indexType。
     */
    public void setIndexType(String indexType) {
        this.indexType = indexType;
    }

    /**
     * 获取indexParameters。
     */
    public String getIndexParameters() {
        return indexParameters;
    }

    /**
     * 设置indexParameters。
     */
    public void setIndexParameters(String indexParameters) {
        this.indexParameters = indexParameters;
    }

    /**
     * 获取embeddingDimension。
     */
    public Integer getEmbeddingDimension() {
        return embeddingDimension;
    }

    /**
     * 设置embeddingDimension。
     */
    public void setEmbeddingDimension(Integer embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    /**
     * 获取maxTextLength。
     */
    public Integer getMaxTextLength() {
        return maxTextLength;
    }

    /**
     * 设置maxTextLength。
     */
    public void setMaxTextLength(Integer maxTextLength) {
        this.maxTextLength = maxTextLength;
    }
}
