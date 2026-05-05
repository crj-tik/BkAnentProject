package com.bkanent.marketing.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.marketing.service.MarketingAssetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/marketing")
public class MarketingContentController {

    private final MarketingAssetService marketingAssetService;

    public MarketingContentController(MarketingAssetService marketingAssetService) {
        this.marketingAssetService = marketingAssetService;
    }

    @GetMapping("/contents")
    public ApiResponse<List<MarketingContentDTO>> list(@RequestParam Long listingId) {
        return ApiResponse.ok(marketingAssetService.listByListingId(listingId));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("marketing-content-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
