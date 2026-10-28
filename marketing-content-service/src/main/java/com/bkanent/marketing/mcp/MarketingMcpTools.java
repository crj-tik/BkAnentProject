package com.bkanent.marketing.mcp;

import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.marketing.campaign.CampaignConfirmRequest;
import com.bkanent.common.marketing.campaign.CampaignPlanPrepareRequest;
import com.bkanent.common.marketing.campaign.CampaignPlanResponse;
import com.bkanent.common.marketing.campaign.CampaignReceiptResponse;
import com.bkanent.common.marketing.campaign.CampaignTargetRequest;
import com.bkanent.common.marketing.campaign.MarketingContentDraftResponse;
import com.bkanent.common.marketing.campaign.MarketingContentDraftsResponse;
import com.bkanent.common.rpc.MediaWorkerRpcService;
import com.bkanent.common.tool.McpTool;
import com.bkanent.marketing.campaign.CampaignDomainException;
import com.bkanent.marketing.campaign.CampaignLifecycleService;
import com.bkanent.marketing.service.MarketingAssetService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class MarketingMcpTools implements McpTool {

    private final MarketingAssetService marketingAssetService;
    private final MediaWorkerRpcService mediaWorkerRpcService;
    private final CampaignLifecycleService campaignLifecycleService;
    private final ObjectMapper objectMapper;

    public MarketingMcpTools(MarketingAssetService marketingAssetService,
                             ObjectProvider<MediaWorkerRpcService> mediaWorkerRpcServiceProvider,
                             CampaignLifecycleService campaignLifecycleService,
                             ObjectMapper objectMapper) {
        this.marketingAssetService = marketingAssetService;
        this.mediaWorkerRpcService = mediaWorkerRpcServiceProvider.getIfAvailable();
        this.campaignLifecycleService = campaignLifecycleService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "生成并保存营销内容草稿。此工具不会发布；必须先准备并由用户确认确切计划。")
    public MarketingContentDraftsResponse createMarketingContentDraft(
            @ToolParam(description = "Listing ID") Long listingId,
            @ToolParam(description = "Comma-separated platforms, e.g. 'douyin,wechat,xiaohongshu'") String platforms,
            @ToolParam(description = "Marketing copywriting text") String copywriting) {
        List<String> platformList = parsePlatforms(platforms);
        List<String> assets = mediaWorkerRpcService == null ? List.of() : mediaWorkerRpcService.generateAssets(listingId, copywriting);
        List<MarketingContentDTO> contents = platformList.stream()
                .map(platform -> new MarketingContentDTO(null, listingId, platform, null, "TEXT", copywriting,
                        assets, null, null, 1, "DEFAULT", List.of("agent-generated"), "DRAFT", "DRAFT"))
                .toList();
        List<MarketingContentDTO> savedContents = marketingAssetService.saveContents(contents);
        List<MarketingContentDraftResponse> drafts = savedContents.stream()
                .map(content -> new MarketingContentDraftResponse(content.id(), content.platform(), content.status()))
                .toList();
        return new MarketingContentDraftsResponse(drafts);
    }

    @Tool(description = "为已审核通过的营销内容创建受权发布计划。此操作仅准备，不执行平台发布。")
    public CampaignPlanResponse prepareMarketingCampaign(Long contentId, String targetsJson, String brandAssetIds) {
        try {
            List<CampaignTargetRequest> targets = objectMapper.readValue(targetsJson, new TypeReference<>() { });
            List<Long> assets = brandAssetIds == null || brandAssetIds.isBlank() ? List.of()
                    : java.util.Arrays.stream(brandAssetIds.split(",")).map(String::trim).map(Long::parseLong).toList();
            return campaignLifecycleService.prepare(new CampaignPlanPrepareRequest(contentId, assets, targets, null));
        } catch (CampaignDomainException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("targetsJson or brandAssetIds is invalid", failure);
        }
    }

    @Tool(description = "Confirm the exact prepared plan snapshot. Call only after the user explicitly confirms this plan and its targets.")
    public CampaignReceiptResponse confirmMarketingCampaign(String planId, String requestId,
                                                             Integer expectedPlanVersion, String expectedSnapshotHash) {
        if (expectedPlanVersion == null) throw new IllegalArgumentException("expectedPlanVersion is required");
        return campaignLifecycleService.confirm(planId,
                new CampaignConfirmRequest(requestId, expectedPlanVersion, expectedSnapshotHash));
    }

    @Tool(description = "Read the publication receipt and per-target outcomes. A receipt is evidence of provider results, not a new publish request.")
    public CampaignReceiptResponse getMarketingCampaignReceipt(
            @ToolParam(description = "Receipt ID returned by confirmMarketingCampaign") String receiptId) {
        return campaignLifecycleService.getReceipt(receiptId);
    }

    @Tool(description = "Reconcile an OUTCOME_UNKNOWN result using the original attempt ID. This never creates or resends an attempt.")
    public CampaignReceiptResponse reconcileMarketingCampaignAttempt(
            @ToolParam(description = "Original attempt ID whose outcome is OUTCOME_UNKNOWN") String attemptId) {
        return campaignLifecycleService.reconcile(attemptId);
    }

    private List<String> parsePlatforms(String platforms) {
        if (platforms == null || platforms.isBlank()) {
            return List.of("douyin");
        }
        return Arrays.stream(platforms.split("[,，、\\s]+"))
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }
}
