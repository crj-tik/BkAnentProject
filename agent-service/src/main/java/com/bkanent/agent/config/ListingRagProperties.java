package com.bkanent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ListingRagProperties 配置属性类。
 */
@ConfigurationProperties(prefix = "agent.rag")
public class ListingRagProperties {

    /**
     * 字段：keywordTopK。
     */
    private int keywordTopK = 20;

    /**
     * 字段：vectorTopK。
     */
    private int vectorTopK = 20;

    /**
     * 字段：rerankTopK。
     */
    private int rerankTopK = 10;

    /**
     * 字段：vectorSimilarityThreshold。
     */
    private Double vectorSimilarityThreshold = 0.0D;

    /**
     * 字段：rerankEndpoint。
     */
    private String rerankEndpoint;

    /**
     * 字段：rerankApiKey。
     */
    private String rerankApiKey;

    /**
     * 字段：rerankModel。
     */
    private String rerankModel = "qwen3-rerank";

    /**
     * 字段：rerankProtocol：rerank 服务协议。dashscope（云端 DashScope 兼容）或 native（本地 bge-reranker /rerank）。
     */
    private RerankProtocol rerankProtocol = RerankProtocol.DASHSCOPE;

    /**
     * 枚举：RerankProtocol。
     */
    public enum RerankProtocol {
        /**
         * 值：DASHSCOPE。
         */
        DASHSCOPE,
        /**
         * 值：NATIVE。
         */
        NATIVE
    }

    /**
     * 获取keywordTopK。
     */
    public int getKeywordTopK() {
        return keywordTopK;
    }

    /**
     * 设置keywordTopK。
     */
    public void setKeywordTopK(int keywordTopK) {
        this.keywordTopK = keywordTopK;
    }

    /**
     * 获取vectorTopK。
     */
    public int getVectorTopK() {
        return vectorTopK;
    }

    /**
     * 设置vectorTopK。
     */
    public void setVectorTopK(int vectorTopK) {
        this.vectorTopK = vectorTopK;
    }

    /**
     * 获取rerankTopK。
     */
    public int getRerankTopK() {
        return rerankTopK;
    }

    /**
     * 设置rerankTopK。
     */
    public void setRerankTopK(int rerankTopK) {
        this.rerankTopK = rerankTopK;
    }

    /**
     * 获取vectorSimilarityThreshold。
     */
    public Double getVectorSimilarityThreshold() {
        return vectorSimilarityThreshold;
    }

    /**
     * 设置vectorSimilarityThreshold。
     */
    public void setVectorSimilarityThreshold(Double vectorSimilarityThreshold) {
        this.vectorSimilarityThreshold = vectorSimilarityThreshold;
    }

    /**
     * 获取rerankEndpoint。
     */
    public String getRerankEndpoint() {
        return rerankEndpoint;
    }

    /**
     * 设置rerankEndpoint。
     */
    public void setRerankEndpoint(String rerankEndpoint) {
        this.rerankEndpoint = rerankEndpoint;
    }

    /**
     * 获取rerankApiKey。
     */
    public String getRerankApiKey() {
        return rerankApiKey;
    }

    /**
     * 设置rerankApiKey。
     */
    public void setRerankApiKey(String rerankApiKey) {
        this.rerankApiKey = rerankApiKey;
    }

    /**
     * 获取rerankModel。
     */
    public String getRerankModel() {
        return rerankModel;
    }

    /**
     * 设置rerankModel。
     */
    public void setRerankModel(String rerankModel) {
        this.rerankModel = rerankModel;
    }

    /**
     * 获取rerankProtocol。
     */
    public RerankProtocol getRerankProtocol() {
        return rerankProtocol;
    }

    /**
     * 设置rerankProtocol。
     */
    public void setRerankProtocol(RerankProtocol rerankProtocol) {
        this.rerankProtocol = rerankProtocol;
    }
}
