package com.bkanent.listing.source;

import com.bkanent.common.listing.ListingDataMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "listing.source")
public class ListingSourceProperties {
    private String provider = "legacy-local";
    private String datasetId = "legacy-local";
    private long fixtureBrokerId = 1L;
    private final Http http = new Http();

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public long getFixtureBrokerId() { return fixtureBrokerId; }
    public void setFixtureBrokerId(long fixtureBrokerId) { this.fixtureBrokerId = fixtureBrokerId; }
    public Http getHttp() { return http; }

    public static class Http {
        private String baseUrl = "";
        private String bearerToken = "";
        private String sourceSystem = "upstream";
        private ListingDataMode confirmedDataMode = ListingDataMode.UNKNOWN;
        private String searchPath = "/listings/business/v1/search";
        private String batchReadPath = "/listings/business/v1/snapshots/batch-read";
        private String checkPath = "/listings/business/v1/snapshots/check";
        private int connectTimeoutMs = 1000;
        private int readTimeoutMs = 3000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getBearerToken() { return bearerToken; }
        public void setBearerToken(String bearerToken) { this.bearerToken = bearerToken; }
        public String getSourceSystem() { return sourceSystem; }
        public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
        public ListingDataMode getConfirmedDataMode() { return confirmedDataMode; }
        public void setConfirmedDataMode(ListingDataMode confirmedDataMode) { this.confirmedDataMode = confirmedDataMode; }
        public String getSearchPath() { return searchPath; }
        public void setSearchPath(String searchPath) { this.searchPath = searchPath; }
        public String getBatchReadPath() { return batchReadPath; }
        public void setBatchReadPath(String batchReadPath) { this.batchReadPath = batchReadPath; }
        public String getCheckPath() { return checkPath; }
        public void setCheckPath(String checkPath) { this.checkPath = checkPath; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    }
}
