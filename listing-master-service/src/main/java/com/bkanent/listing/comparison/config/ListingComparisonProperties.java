package com.bkanent.listing.comparison.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "listing.comparison.report")
public class ListingComparisonProperties {
    private String storageDir = "/app/runtime/reports";

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
}
