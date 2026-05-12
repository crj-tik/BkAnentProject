package com.bkanent.promotion.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.model.PublishRequest;
import com.bkanent.common.rpc.MarketingContentRpcService;
import com.bkanent.promotion.model.BrandAssetResponse;
import com.bkanent.promotion.model.BrandAssetUpsertRequest;
import com.bkanent.promotion.model.PromotionEffectResponse;
import com.bkanent.promotion.model.PromotionEffectSyncRequest;
import com.bkanent.promotion.model.PromotionPublishRequest;
import com.bkanent.promotion.model.PromotionPublishResultResponse;
import com.bkanent.promotion.model.PromotionRoiReportResponse;
import com.bkanent.promotion.service.PromotionManagementService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Promotion operations controller.
 */
@RestController
@RequestMapping("/promotions")
public class PromotionController {

    private final PromotionManagementService promotionManagementService;

    @DubboReference(check = false)
    private MarketingContentRpcService marketingContentRpcService;

    public PromotionController(PromotionManagementService promotionManagementService) {
        this.promotionManagementService = promotionManagementService;
    }

    @PostMapping("/publish")
    public ApiResponse<List<PromotionPublishResultResponse>> publish(@RequestBody PublishRequest request) {
        List<MarketingContentDTO> contents = request.platforms().stream()
                .map(platform -> new MarketingContentDTO(request.listingId(), platform, request.prompt(), List.of(), "REVIEWING"))
                .toList();
        List<MarketingContentDTO> savedContents = marketingContentRpcService == null
                ? contents
                : marketingContentRpcService.saveGeneratedContents(contents);
        List<PromotionPublishResultResponse> results = new ArrayList<>();
        for (int index = 0; index < request.platforms().size(); index++) {
            MarketingContentDTO content = savedContents.get(index);
            String platform = request.platforms().get(index);
            results.add(promotionManagementService.publishContent(content, new PromotionPublishRequest(
                    content.id(),
                    request.listingId(),
                    platform,
                    platform.toLowerCase() + "_official",
                    "SYSTEM",
                    BigDecimal.valueOf(100)
            )));
        }
        return ApiResponse.ok(results);
    }

    @PostMapping("/effects/sync")
    public ApiResponse<PromotionEffectResponse> syncEffects(@RequestBody PromotionEffectSyncRequest request) {
        return ApiResponse.ok(promotionManagementService.syncEffectStat(request));
    }

    @GetMapping("/effects")
    public ApiResponse<List<PromotionEffectResponse>> listEffects(@RequestParam Long listingId) {
        return ApiResponse.ok(promotionManagementService.listEffectStats(listingId));
    }

    @GetMapping("/roi-report")
    public ApiResponse<List<PromotionRoiReportResponse>> roiReport() {
        return ApiResponse.ok(promotionManagementService.buildRoiReport());
    }

    @PostMapping("/brand-assets")
    public ApiResponse<BrandAssetResponse> saveBrandAsset(@RequestBody BrandAssetUpsertRequest request) {
        return ApiResponse.ok(promotionManagementService.saveBrandAsset(request));
    }

    @GetMapping("/brand-assets")
    public ApiResponse<List<BrandAssetResponse>> listBrandAssets(@RequestParam(required = false) String assetType,
                                                                 @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(promotionManagementService.listBrandAssets(assetType, keyword));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("promotion-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
