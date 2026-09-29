package com.bkanent.interview.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 报告任务租约配置。
 */
@ConfigurationProperties(prefix = "interview.report")
public class InterviewReportProperties {

    private int leaseMinutes = 10;
    private int renewRatio = 3;
    private int maxRetries = 3;

    public int getLeaseMinutes() { return leaseMinutes; }
    public void setLeaseMinutes(int leaseMinutes) { this.leaseMinutes = leaseMinutes; }
    public int getRenewRatio() { return renewRatio; }
    public void setRenewRatio(int renewRatio) { this.renewRatio = renewRatio; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
}
