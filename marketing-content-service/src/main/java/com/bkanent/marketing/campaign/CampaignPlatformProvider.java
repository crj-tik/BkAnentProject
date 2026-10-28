package com.bkanent.marketing.campaign;

public interface CampaignPlatformProvider {
    boolean simulated();
    boolean supports(String platform);
    CampaignProviderResult publish(CampaignAttemptData attempt, CampaignTargetData target);
    CampaignProviderResult reconcile(CampaignAttemptData attempt);
    CampaignEffectObservation readEffect(CampaignAttemptData attempt);
}
