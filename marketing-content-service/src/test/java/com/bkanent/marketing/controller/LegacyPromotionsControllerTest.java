package com.bkanent.marketing.controller;

import com.bkanent.common.marketing.campaign.CampaignConfirmRequest;
import com.bkanent.common.marketing.campaign.CampaignDataMode;
import com.bkanent.common.marketing.campaign.CampaignPlanPrepareRequest;
import com.bkanent.common.marketing.campaign.CampaignPlanResponse;
import com.bkanent.common.marketing.campaign.CampaignAttemptReceipt;
import com.bkanent.common.marketing.campaign.CampaignReceiptResponse;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.marketing.campaign.CampaignAssetAdminService;
import com.bkanent.marketing.campaign.CampaignLifecycleService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyPromotionsControllerTest {

    @Test
    void compatibilityRoutesDelegateToTheAuthenticatedCampaignLifecycle() {
        CampaignLifecycleService campaigns = mock(CampaignLifecycleService.class);
        LegacyPromotionsController controller = new LegacyPromotionsController(campaigns, mock(CampaignAssetAdminService.class));
        CampaignPlanPrepareRequest prepareRequest = new CampaignPlanPrepareRequest(5L, List.of(), List.of(), null);
        var plan = new CampaignPlanResponse("plan-1", 5L, 1, "content-hash", "asset-hash", "targets-hash",
                "rules-v1", "snapshot-hash", 1, "PREPARED", CampaignDataMode.SIMULATED, List.of(),
                LocalDateTime.parse("2026-01-01T00:00:00"), LocalDateTime.parse("2026-01-01T00:15:00"));
        when(campaigns.prepare(prepareRequest)).thenReturn(plan);
        assertEquals(ApiResponse.ok(plan), controller.prepare(prepareRequest));
        verify(campaigns).prepare(prepareRequest);

        CampaignConfirmRequest confirmRequest = new CampaignConfirmRequest("request-1", 1, "snapshot-hash");
        var receipt = new CampaignReceiptResponse("receipt-1", "plan-1", "request-1", "ACCEPTED",
                CampaignDataMode.SIMULATED, LocalDateTime.parse("2026-01-01T00:01:00"),
                List.of(new CampaignAttemptReceipt("attempt-1", 0, "DOUYIN", 1L, "ACCEPTED", null,
                        "accepted", CampaignDataMode.SIMULATED, LocalDateTime.parse("2026-01-01T00:01:00"))));
        when(campaigns.confirm("plan-1", confirmRequest)).thenReturn(receipt);
        assertEquals(ApiResponse.ok(receipt), controller.confirm("plan-1", confirmRequest));
        verify(campaigns).confirm("plan-1", confirmRequest);
    }
}
