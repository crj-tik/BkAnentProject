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
import com.bkanent.marketing.campaign.CampaignDomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
        LegacyPromotionsController controller = new LegacyPromotionsController(campaigns,
                mock(CampaignAssetAdminService.class), new ObjectMapper());
        var plan = new CampaignPlanResponse("plan-1", 5L, 1, "content-hash", "asset-hash", "targets-hash",
                "rules-v1", "snapshot-hash", 1, "PREPARED", CampaignDataMode.SIMULATED, List.of(),
                LocalDateTime.parse("2026-01-01T00:00:00"), LocalDateTime.parse("2026-01-01T00:15:00"));
        ObjectNode newRequest = new ObjectMapper().createObjectNode();
        newRequest.put("contentId", 5L);
        newRequest.putArray("brandAssetIds");
        newRequest.putArray("targets");
        var parsedRequest = new CampaignPlanPrepareRequest(5L, List.of(), List.of(), null);
        when(campaigns.prepare(parsedRequest)).thenReturn(plan);
        assertEquals(ApiResponse.ok(plan), controller.prepare(newRequest));
        verify(campaigns).prepare(parsedRequest);

        ObjectNode oldRequest = new ObjectMapper().createObjectNode();
        oldRequest.put("listingId", 5L);
        oldRequest.putArray("platforms").add("douyin");
        oldRequest.put("prompt", "发布房源");
        var legacyFailure = org.assertj.core.api.Assertions.catchThrowable(() -> controller.prepare(oldRequest));
        org.assertj.core.api.Assertions.assertThat(legacyFailure).isInstanceOf(CampaignDomainException.class);
        var campaignFailure = (CampaignDomainException) legacyFailure;
        assertEquals(org.springframework.http.HttpStatus.GONE, campaignFailure.status());
        assertEquals(com.bkanent.common.marketing.campaign.CampaignErrorCode.LEGACY_CONTRACT_UNSUPPORTED,
                campaignFailure.code());
        assertEquals(org.springframework.http.HttpStatus.GONE,
                new CampaignExceptionHandler().campaignFailure(campaignFailure).getStatusCode());

        ObjectNode partialOldRequest = new ObjectMapper().createObjectNode();
        partialOldRequest.put("listingId", 5L);
        partialOldRequest.put("prompt", "旧客户端省略平台");
        var partialFailure = org.assertj.core.api.Assertions.catchThrowable(() -> controller.prepare(partialOldRequest));
        org.assertj.core.api.Assertions.assertThat(partialFailure).isInstanceOf(CampaignDomainException.class);
        assertEquals(com.bkanent.common.marketing.campaign.CampaignErrorCode.LEGACY_CONTRACT_UNSUPPORTED,
                ((CampaignDomainException) partialFailure).code());

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
