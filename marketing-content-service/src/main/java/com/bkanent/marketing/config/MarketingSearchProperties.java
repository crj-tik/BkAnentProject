package com.bkanent.marketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 营销搜索业务配置。
 */
@ConfigurationProperties(prefix = "marketing.search")
public class MarketingSearchProperties {

    /**
     * 是否启用 Elasticsearch 查询。
     */
    /**
     * 业务属性：useElasticsearch。
     */
    private boolean useElasticsearch = true;

    /**
     * 是否允许应用侧直接写入 Elasticsearch。
     * 使用 Canal 从 MySQL 同步到 Elasticsearch 时应保持关闭。
     */
    /**
     * 业务属性：writeEnabled。
     */
    private boolean writeEnabled = false;

    /**
     * Elasticsearch 索引名称。
     */
    /**
     * 业务属性：indexName。
     */
    private String indexName = "marketing_content";

    /**
     * 搜索同步模式。
     */
    /**
     * 业务属性：syncMode。
     */
    private String syncMode = "CANAL";

    public boolean isUseElasticsearch() {
        return useElasticsearch;
    }

    public void setUseElasticsearch(boolean useElasticsearch) {
        this.useElasticsearch = useElasticsearch;
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public void setWriteEnabled(boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    public String getIndexName() {
        return indexName;
    }

    public void setIndexName(String indexName) {
        this.indexName = indexName;
    }

    public String getSyncMode() {
        return syncMode;
    }

    public void setSyncMode(String syncMode) {
        this.syncMode = syncMode;
    }
}
