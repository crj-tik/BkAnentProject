package com.bkanent.businessreview.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "business-review.sources")
public class DomainParallelProperties {
    private String mode;
    private String listingBaseUrl;
    private String knowledgeBaseUrl;
    private String listingAudience = "listing-master-service";
    private String knowledgeAudience = "knowledge-service";
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 5000;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getListingBaseUrl() { return listingBaseUrl; }
    public void setListingBaseUrl(String value) { listingBaseUrl = value; }
    public String getKnowledgeBaseUrl() { return knowledgeBaseUrl; }
    public void setKnowledgeBaseUrl(String value) { knowledgeBaseUrl = value; }
    public String getListingAudience() { return listingAudience; }
    public void setListingAudience(String value) { listingAudience = value; }
    public String getKnowledgeAudience() { return knowledgeAudience; }
    public void setKnowledgeAudience(String value) { knowledgeAudience = value; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int value) { readTimeoutMs = value; }
}
