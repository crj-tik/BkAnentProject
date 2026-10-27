package com.bkanent.marketing.controller;

import com.bkanent.common.marketing.campaign.CampaignConfirmRequest;
import com.bkanent.common.marketing.campaign.CampaignPlanPrepareRequest;
import com.bkanent.common.marketing.campaign.CampaignPlanResponse;
import com.bkanent.common.marketing.campaign.CampaignReceiptResponse;
import com.bkanent.common.marketing.campaign.CampaignEffectResponse;
import com.bkanent.common.marketing.campaign.CampaignRoiReportResponse;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.marketing.campaign.CampaignAssetAdminService;
import com.bkanent.marketing.campaign.CampaignBrandAssetRequest;
import com.bkanent.marketing.campaign.CampaignBrandAssetResponse;
import com.bkanent.marketing.campaign.CampaignLifecycleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;

/** Compatibility routes that now delegate into the authenticated campaign domain. */
@RestController
@RequestMapping("/promotions")
public class LegacyPromotionsController {
    private final CampaignLifecycleService campaigns;
    private final CampaignAssetAdminService assets;
    private final ObjectMapper objectMapper;

    public LegacyPromotionsController(CampaignLifecycleService campaigns, CampaignAssetAdminService assets,
                                     ObjectMapper objectMapper) {
        this.campaigns = campaigns;
        this.assets = assets;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/publish")
    public ApiResponse<CampaignPlanResponse> prepare(@RequestBody JsonNode body) {
        if (body != null && !body.has("contentId")
                && (body.has("listingId") || body.has("platforms") || body.has("prompt"))) {
            throw com.bkanent.marketing.campaign.CampaignDomainException.legacyContractUnsupported(
                    "旧 /promotions/publish 请求体已停止支持；请迁移到营销内容草稿、审核、campaign prepare、confirm 和 receipt/reconcile 流程");
        }
        try {
            CampaignPlanPrepareRequest request = objectMapper.treeToValue(body, CampaignPlanPrepareRequest.class);
            return ApiResponse.ok(campaigns.prepare(request));
        } catch (com.bkanent.marketing.campaign.CampaignDomainException failure) {
            throw failure;
        } catch (Exception failure) {
            throw com.bkanent.marketing.campaign.CampaignDomainException.invalid(
                    "营销计划请求体无效；请使用 contentId、brandAssetIds、targets 和 requestId");
        }
    }

    @PostMapping("/{planId}/confirm")
    public ApiResponse<CampaignReceiptResponse> confirm(@PathVariable String planId,
                                                        @RequestBody CampaignConfirmRequest request) {
        return ApiResponse.ok(campaigns.confirm(planId, request));
    }

    @GetMapping("/receipts/{receiptId}")
    public ApiResponse<CampaignReceiptResponse> receipt(@PathVariable String receiptId) {
        return ApiResponse.ok(campaigns.getReceipt(receiptId));
    }

    @PostMapping("/effects/sync")
    public ApiResponse<CampaignEffectResponse> syncEffect(@RequestBody LegacyEffectSyncRequest request) {
        if (request == null || request.publishRecordId() == null) throw com.bkanent.marketing.campaign.CampaignDomainException.invalid("publishRecordId is required");
        return ApiResponse.ok(campaigns.syncLegacyEffect(request.publishRecordId()));
    }

    @GetMapping("/effects")
    public ApiResponse<List<CampaignEffectResponse>> effects(@RequestParam Long listingId) {
        return ApiResponse.ok(campaigns.effectsForListing(listingId));
    }

    @GetMapping("/roi-report")
    public ApiResponse<List<CampaignRoiReportResponse>> roiReport() { return ApiResponse.ok(campaigns.roiReport(null)); }

    @PostMapping("/brand-assets")
    public ApiResponse<CampaignBrandAssetResponse> saveBrandAsset(@RequestBody CampaignBrandAssetRequest request) {
        return ApiResponse.ok(assets.create(request));
    }

    @GetMapping("/brand-assets")
    public ApiResponse<List<CampaignBrandAssetResponse>> brandAssets(@RequestParam(required = false) String assetType,
                                                                     @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(assets.list(assetType, keyword));
    }

    @GetMapping("/health")
    public ApiResponse<String> health() { return ApiResponse.ok("promotions routes are served by marketing-content-service"); }
}
