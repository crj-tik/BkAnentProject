package com.bkanent.promotion.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.MarketingContentDTO;
import com.bkanent.common.model.PublishRequest;
import com.bkanent.common.rpc.PromotionRpcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/promotions")
public class PromotionController {

    private final PromotionRpcService promotionRpcService;

    public PromotionController(PromotionRpcService promotionRpcService) {
        this.promotionRpcService = promotionRpcService;
    }

    @PostMapping("/publish")
    public ApiResponse<List<String>> publish(@RequestBody PublishRequest request) {
        List<String> results = request.platforms().stream()
                .map(platform -> promotionRpcService.publish(new MarketingContentDTO(request.listingId(), platform, request.prompt(), List.of(), "READY")))
                .toList();
        return ApiResponse.ok(results);
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("promotion-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
