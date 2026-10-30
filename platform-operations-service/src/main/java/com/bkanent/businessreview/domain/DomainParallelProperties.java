package com.bkanent.businessreview.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform-operations.review.sources")
public class DomainParallelProperties {
    private String mode;
    private String listingBaseUrl;
    private String listingAudience = "listing-master-service";
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 5000;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getListingBaseUrl() { return listingBaseUrl; }
    public void setListingBaseUrl(String value) { listingBaseUrl = value; }
    public String getListingAudience() { return listingAudience; }
    public void setListingAudience(String value) { listingAudience = value; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int value) { readTimeoutMs = value; }
}
