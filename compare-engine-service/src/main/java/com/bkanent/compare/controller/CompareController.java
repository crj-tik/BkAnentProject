package com.bkanent.compare.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.rpc.CompareEngineRpcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/compare")
public class CompareController {

    private final CompareEngineRpcService compareEngineRpcService;

    public CompareController(CompareEngineRpcService compareEngineRpcService) {
        this.compareEngineRpcService = compareEngineRpcService;
    }

    @GetMapping("/listings")
    public ApiResponse<CompareReportDTO> compare(@RequestParam List<Long> listingIds) {
        return ApiResponse.ok(compareEngineRpcService.compareListings(listingIds));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("compare-engine-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
