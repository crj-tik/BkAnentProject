package com.bkanent.agent.milvus.listing;

import com.bkanent.common.model.ListingDTO;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ListingRecallCandidate 组件。
 */
public class ListingRecallCandidate {

    /**
     * 字段：listing。
     */
    private final ListingDTO listing;
    /**
     * 字段：content。
     */
    private final String content;
    private final Set<String> recallSources = new LinkedHashSet<>();
    /**
     * 字段：keywordScore。
     */
    private Double keywordScore;
    /**
     * 字段：vectorScore。
     */
    private Double vectorScore;
    /**
     * 字段：rerankScore。
     */
    private Double rerankScore;

    /**
     * 处理ListingRecallCandidate。
     */
    public ListingRecallCandidate(ListingDTO listing, String content) {
        this.listing = listing;
        this.content = content;
    }

    /**
     * 获取listing。
     */
    public ListingDTO getListing() {
        return listing;
    }

    /**
     * 获取content。
     */
    public String getContent() {
        return content;
    }

    /**
     * 获取recallSources。
     */
    public Set<String> getRecallSources() {
        return recallSources;
    }

    /**
     * 获取keywordScore。
     */
    public Double getKeywordScore() {
        return keywordScore;
    }

    /**
     * 设置keywordScore。
     */
    public void setKeywordScore(Double keywordScore) {
        this.keywordScore = keywordScore;
    }

    /**
     * 获取vectorScore。
     */
    public Double getVectorScore() {
        return vectorScore;
    }

    /**
     * 设置vectorScore。
     */
    public void setVectorScore(Double vectorScore) {
        this.vectorScore = vectorScore;
    }

    /**
     * 获取rerankScore。
     */
    public Double getRerankScore() {
        return rerankScore;
    }

    /**
     * 设置rerankScore。
     */
    public void setRerankScore(Double rerankScore) {
        this.rerankScore = rerankScore;
    }
}
