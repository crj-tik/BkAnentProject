package com.bkanent.marketing.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.marketing.model.MarketingContentDetailResponse;
import com.bkanent.marketing.model.MarketingContentSearchRequest;
import com.bkanent.marketing.model.MarketingContentUpsertRequest;
import com.bkanent.marketing.model.MarketingPublishStatusUpdateRequest;
import com.bkanent.marketing.service.MarketingAssetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Marketing content controller.
 */
@RestController
@RequestMapping("/marketing")
public class MarketingContentController {

    private final MarketingAssetService marketingAssetService;

    public MarketingContentController(MarketingAssetService marketingAssetService) {
        this.marketingAssetService = marketingAssetService;
    }

    @PostMapping("/contents")
    public ApiResponse<MarketingContentDetailResponse> create(@RequestBody MarketingContentUpsertRequest request) {
        return ApiResponse.ok(marketingAssetService.createContent(request));
    }

    @PostMapping("/contents/{id}/variants")
    public ApiResponse<MarketingContentDetailResponse> createVariant(@PathVariable Long id,
                                                                     @RequestBody MarketingContentUpsertRequest request) {
        return ApiResponse.ok(marketingAssetService.createPlatformVariant(id, request));
    }

    @PutMapping("/contents/{id}/publish-status")
    public ApiResponse<MarketingContentDetailResponse> updatePublishStatus(@PathVariable Long id,
                                                                           @RequestBody MarketingPublishStatusUpdateRequest request) {
        return ApiResponse.ok(marketingAssetService.updatePublishStatus(id, request));
    }

    @GetMapping("/contents")
    public ApiResponse<List<MarketingContentDTO>> list(@RequestParam Long listingId) {
        return ApiResponse.ok(marketingAssetService.listByListingId(listingId));
    }

    @GetMapping("/contents/search")
    public ApiResponse<List<MarketingContentDetailResponse>> search(@RequestParam(required = false) Long listingId,
                                                                    @RequestParam(required = false) String platform,
                                                                    @RequestParam(required = false) String keyword,
                                                                    @RequestParam(required = false) String tag,
                                                                    @RequestParam(required = false) String contentType,
                                                                    @RequestParam(required = false) String auditStatus,
                                                                    @RequestParam(required = false) String publishStatus) {
        return ApiResponse.ok(marketingAssetService.searchContents(new MarketingContentSearchRequest(
                listingId,
                platform,
                keyword,
                tag,
                contentType,
                auditStatus,
                publishStatus
        )));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("marketing-content-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
