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
import com.bkanent.marketing.campaign.CampaignContentReviewRequest;
import com.bkanent.marketing.campaign.CampaignContentReviewService;
import com.bkanent.marketing.model.MarketingContentDetailResponse;
import com.bkanent.marketing.campaign.CampaignLifecycleService;
import org.springframework.web.bind.annotation.RequestParam;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/marketing/campaigns")
public class CampaignController {
    private final CampaignLifecycleService campaigns;
    private final CampaignAssetAdminService assets;
    private final CampaignContentReviewService contentReview;

    public CampaignController(CampaignLifecycleService campaigns, CampaignAssetAdminService assets,
                              CampaignContentReviewService contentReview) {
        this.campaigns = campaigns; this.assets = assets; this.contentReview = contentReview;
    }

    @PostMapping("/prepare")
    public ApiResponse<CampaignPlanResponse> prepare(@RequestBody CampaignPlanPrepareRequest request) {
        return ApiResponse.ok(campaigns.prepare(request));
    }

    @PostMapping("/contents/{contentId}/review")
    public ApiResponse<MarketingContentDetailResponse> reviewContent(@PathVariable long contentId,
                                                                     @RequestBody CampaignContentReviewRequest request) {
        return ApiResponse.ok(contentReview.review(contentId, request));
    }

    @GetMapping("/plans/{planId}")
    public ApiResponse<CampaignPlanResponse> getPlan(@PathVariable String planId) {
        return ApiResponse.ok(campaigns.getPlan(planId));
    }

    @PostMapping("/plans/{planId}/confirm")
    public ApiResponse<CampaignReceiptResponse> confirm(@PathVariable String planId,
                                                        @RequestBody CampaignConfirmRequest request) {
        return ApiResponse.ok(campaigns.confirm(planId, request));
    }

    @GetMapping("/plans/{planId}/receipts/{requestId}")
    public ApiResponse<CampaignReceiptResponse> receipt(@PathVariable String planId, @PathVariable String requestId) {
        return ApiResponse.ok(campaigns.findReceipt(planId, requestId));
    }

    @GetMapping("/receipts/{receiptId}")
    public ApiResponse<CampaignReceiptResponse> receipt(@PathVariable String receiptId) {
        return ApiResponse.ok(campaigns.getReceipt(receiptId));
    }

    @PostMapping("/attempts/{attemptId}/reconcile")
    public ApiResponse<CampaignReceiptResponse> reconcile(@PathVariable String attemptId) {
        return ApiResponse.ok(campaigns.reconcile(attemptId));
    }

    @GetMapping("/attempts/{attemptId}/effects")
    public ApiResponse<CampaignEffectResponse> effect(@PathVariable String attemptId) {
        return ApiResponse.ok(campaigns.readEffect(attemptId, false));
    }

    @PostMapping("/attempts/{attemptId}/effects/sync")
    public ApiResponse<CampaignEffectResponse> syncEffect(@PathVariable String attemptId) {
        return ApiResponse.ok(campaigns.readEffect(attemptId, true));
    }

    @GetMapping("/roi-report")
    public ApiResponse<List<CampaignRoiReportResponse>> roiReport(@RequestParam(required = false) String platform) {
        return ApiResponse.ok(campaigns.roiReport(platform));
    }

    @PostMapping("/brand-assets")
    public ApiResponse<CampaignBrandAssetResponse> createAsset(@RequestBody CampaignBrandAssetRequest request) {
        return ApiResponse.ok(assets.create(request));
    }

    @GetMapping("/brand-assets")
    public ApiResponse<List<CampaignBrandAssetResponse>> listAssets(@RequestParam(required = false) String assetType,
                                                                    @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(assets.list(assetType, keyword));
    }
}
