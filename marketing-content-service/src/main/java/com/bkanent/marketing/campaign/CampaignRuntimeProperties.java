package com.bkanent.marketing.campaign;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "marketing.campaign")
public class CampaignRuntimeProperties {

    public enum Mode { REAL, SIMULATED }

    private Mode mode = Mode.REAL;
    private String ruleVersion = "campaign-rules-v1";
    private boolean dispatchEnabled = true;
    private long outboxLeaseSeconds = 30;
    private String listingSnapshotBaseUrl = "";
    private long planTtlSeconds = 900;

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode == null ? Mode.REAL : mode; }
    public String getRuleVersion() { return ruleVersion; }
    public void setRuleVersion(String ruleVersion) { this.ruleVersion = ruleVersion; }
    public boolean isDispatchEnabled() { return dispatchEnabled; }
    public void setDispatchEnabled(boolean dispatchEnabled) { this.dispatchEnabled = dispatchEnabled; }
    public long getOutboxLeaseSeconds() { return outboxLeaseSeconds; }
    public void setOutboxLeaseSeconds(long outboxLeaseSeconds) { this.outboxLeaseSeconds = outboxLeaseSeconds; }
    public String getListingSnapshotBaseUrl() { return listingSnapshotBaseUrl; }
    public void setListingSnapshotBaseUrl(String listingSnapshotBaseUrl) { this.listingSnapshotBaseUrl = listingSnapshotBaseUrl; }
    public long getPlanTtlSeconds() { return planTtlSeconds; }
    public void setPlanTtlSeconds(long planTtlSeconds) { this.planTtlSeconds = planTtlSeconds; }
}
