package com.bkanent.marketing.campaign;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CampaignOutboxDispatcher {
    private final CampaignLifecycleService campaigns;
    private final CampaignRuntimeProperties properties;

    public CampaignOutboxDispatcher(CampaignLifecycleService campaigns, CampaignRuntimeProperties properties) {
        this.campaigns = campaigns;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${marketing.campaign.dispatch-delay-ms:1000}")
    public void drain() {
        if (!properties.isDispatchEnabled()) return;
        if (!campaigns.dispatchOne()) campaigns.reconcileOne();
    }
}
