package com.bkanent.business.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.KpiSummaryDTO;
import com.bkanent.common.rpc.BusinessRpcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/business")
public class BusinessController {

    private final BusinessRpcService businessRpcService;

    public BusinessController(BusinessRpcService businessRpcService) {
        this.businessRpcService = businessRpcService;
    }

    @GetMapping("/kpis/monthly")
    public ApiResponse<List<KpiSummaryDTO>> monthlyKpis(@RequestParam String month) {
        return ApiResponse.ok(businessRpcService.getMonthlyKpis(month));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("business-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
