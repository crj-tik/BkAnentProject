package com.bkanent.marketing.campaign;

import com.bkanent.common.marketing.campaign.CampaignDataMode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@Profile("!production")
public class SimulatedCampaignPlatformProvider implements CampaignPlatformProvider {

    @Override public boolean simulated() { return true; }
    @Override public boolean supports(String platform) { return platform != null && !platform.isBlank(); }

    @Override
    public CampaignProviderResult publish(CampaignAttemptData attempt, CampaignTargetData target) {
        String outcome = target.arguments().getOrDefault("fixtureOutcome", "SUCCESS").toUpperCase();
        return switch (outcome) {
            case "FAILED" -> new CampaignProviderResult("FAILED", null, "SIMULATED fixture rejection", CampaignDataMode.SIMULATED);
            case "UNKNOWN", "ACCEPTED_THEN_TIMEOUT" -> new CampaignProviderResult("OUTCOME_UNKNOWN", null,
                    "SIMULATED fixture accepted but response is unknown", CampaignDataMode.SIMULATED);
            default -> new CampaignProviderResult("SUCCESS", "sim-" + attempt.attemptId(),
                    "SIMULATED fixture accepted", CampaignDataMode.SIMULATED);
        };
    }

    @Override
    public CampaignProviderResult reconcile(CampaignAttemptData attempt) {
        return new CampaignProviderResult("SUCCESS", "sim-" + attempt.attemptId(),
                "SIMULATED fixture reconciliation confirmed original attempt", CampaignDataMode.SIMULATED);
    }

    @Override
    public CampaignEffectObservation readEffect(CampaignAttemptData attempt) {
        long sample = Long.parseLong(CampaignHash.sha256(attempt.attemptId()).substring(0, 2), 16);
        long exposure = 40 + sample;
        long clicks = exposure / 5;
        long leads = clicks / 4;
        Instant end = Instant.EPOCH.plus(1, ChronoUnit.DAYS);
        return new CampaignEffectObservation("SIMULATED_FIXTURE", "sim-effect-" + attempt.attemptId(), 1,
                Instant.EPOCH, end, end, exposure, clicks, leads, leads, null,
                java.math.BigDecimal.TEN, "CNY", null, "SIMULATED fixture: revenue attribution is unavailable");
    }
}
