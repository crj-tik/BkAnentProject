package com.bkanent.listing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ListingSearchProperties 配置属性类。
 */
@Component
@ConfigurationProperties(prefix = "listing.search")
public class ListingSearchProperties {

    /**
     * 字段：useElasticsearch。
     */
    private boolean useElasticsearch = true;
    /**
     * 字段：writeEnabled。
     */
    private boolean writeEnabled = false;
    /**
     * 字段：indexName。
     */
    private String indexName = "listing_info";

    /**
     * 判断是否useElasticsearch。
     */
    public boolean isUseElasticsearch() {
        return useElasticsearch;
    }

    /**
     * 设置useElasticsearch。
     */
    public void setUseElasticsearch(boolean useElasticsearch) {
        this.useElasticsearch = useElasticsearch;
    }

    /**
     * 判断是否writeEnabled。
     */
    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    /**
     * 设置writeEnabled。
     */
    public void setWriteEnabled(boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    /**
     * 获取indexName。
     */
    public String getIndexName() {
        return indexName;
    }

    /**
     * 设置indexName。
     */
    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }
}
