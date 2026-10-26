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

    public LegacyPromotionsController(CampaignLifecycleService campaigns, CampaignAssetAdminService assets) { this.campaigns = campaigns; this.assets = assets; }

    @PostMapping("/publish")
    public ApiResponse<CampaignPlanResponse> prepare(@RequestBody CampaignPlanPrepareRequest request) {
        return ApiResponse.ok(campaigns.prepare(request));
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
