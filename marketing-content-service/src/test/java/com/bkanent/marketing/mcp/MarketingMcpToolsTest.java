package com.bkanent.marketing.mcp;

import com.bkanent.common.marketing.campaign.CampaignConfirmRequest;
import com.bkanent.common.marketing.campaign.CampaignDataMode;
import com.bkanent.common.marketing.campaign.CampaignPlanPrepareRequest;
import com.bkanent.common.marketing.campaign.CampaignPlanResponse;
import com.bkanent.common.marketing.campaign.CampaignReceiptResponse;
import com.bkanent.common.marketing.campaign.CampaignTargetRequest;
import com.bkanent.common.marketing.campaign.MarketingContentDraftResponse;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.rpc.MediaWorkerRpcService;
import com.bkanent.marketing.campaign.CampaignLifecycleService;
import com.bkanent.marketing.service.MarketingAssetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingMcpToolsTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<MediaWorkerRpcService> mediaWorkerProvider = mock(ObjectProvider.class);
    private final MarketingAssetService assets = mock(MarketingAssetService.class);
    private final CampaignLifecycleService campaigns = mock(CampaignLifecycleService.class);
    private final MarketingMcpTools tools = new MarketingMcpTools(assets, mediaWorkerProvider, campaigns, new ObjectMapper());

    @Test
    void publishedMcpToolDirectoryMatchesSupervisorAllowlistNames() {
        var callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();

        assertThat(java.util.Arrays.stream(callbacks).map(callback -> callback.getToolDefinition().name()).toList())
                .containsExactlyInAnyOrder("createMarketingContentDraft", "prepareMarketingCampaign",
                        "confirmMarketingCampaign", "getMarketingCampaignReceipt", "reconcileMarketingCampaignAttempt");
    }

    @Test
    void draftToolReturnsPersistedIdentityForPrepareAndNeverConfirms() {
        when(assets.saveContents(any())).thenReturn(List.of(
                content(41L, "DOUYIN"), content(42L, "XIAOHONGSHU")));

        var result = tools.createMarketingContentDraft(9L, "DOUYIN,xiaohongshu", "draft text");

        assertThat(result.contents()).containsExactly(
                new MarketingContentDraftResponse(41L, "DOUYIN", "DRAFT"),
                new MarketingContentDraftResponse(42L, "XIAOHONGSHU", "DRAFT"));
        verify(assets).saveContents(any());
    }

    @Test
    void receiptAndUnknownReconciliationUseTheProvidedReceiptAndOriginalAttemptIds() {
        CampaignReceiptResponse receipt = receipt();
        when(campaigns.getReceipt("receipt-7")).thenReturn(receipt);
        when(campaigns.reconcile("attempt-3")).thenReturn(receipt);

        assertThat(tools.getMarketingCampaignReceipt("receipt-7")).isSameAs(receipt);
        assertThat(tools.reconcileMarketingCampaignAttempt("attempt-3")).isSameAs(receipt);

        verify(campaigns).getReceipt("receipt-7");
        verify(campaigns).reconcile("attempt-3");
    }

    @Test
    void confirmationPassesTheExactPreparedSnapshotAndRequestId() {
        CampaignReceiptResponse receipt = receipt();
        when(campaigns.confirm("plan-2", new CampaignConfirmRequest("request-8", 2, "hash-9"))).thenReturn(receipt);

        assertThat(tools.confirmMarketingCampaign("plan-2", "request-8", 2, "hash-9")).isSameAs(receipt);
        verify(campaigns).confirm("plan-2", new CampaignConfirmRequest("request-8", 2, "hash-9"));
    }

    @Test
    void preparedContentIdentityIsForwardedIntoCampaignLifecycleWithoutConfirmation() {
        CampaignPlanPrepareRequest request = new CampaignPlanPrepareRequest(41L, List.of(),
                List.of(new CampaignTargetRequest("DOUYIN", 5L, java.util.Map.of("title", "approved"))), null);
        CampaignPlanResponse response = new CampaignPlanResponse("plan-1", 41L, 1, "content", "assets", "targets",
                "rules-v1", "snapshot", 1, "PREPARED", CampaignDataMode.SIMULATED, List.of(),
                java.time.LocalDateTime.parse("2026-01-01T00:00:00"), java.time.LocalDateTime.parse("2026-01-01T00:15:00"));
        when(campaigns.prepare(request)).thenReturn(response);

        assertThat(tools.prepareMarketingCampaign(41L,
                "[{\"platform\":\"DOUYIN\",\"accountId\":5,\"arguments\":{\"title\":\"approved\"}}]", ""))
                .isEqualTo(response);
        verify(campaigns).prepare(request);
    }

    private MarketingContentDTO content(Long id, String platform) {
        return new MarketingContentDTO(id, 9L, platform, null, "TEXT", "draft text", List.of(), null, null,
                1, "DEFAULT", List.of(), "DRAFT", "DRAFT");
    }

    private CampaignReceiptResponse receipt() {
        return new CampaignReceiptResponse("receipt-1", "plan-1", "request-1", "ACCEPTED",
                CampaignDataMode.SIMULATED, java.time.LocalDateTime.parse("2026-01-01T00:01:00"), List.of());
    }
}
