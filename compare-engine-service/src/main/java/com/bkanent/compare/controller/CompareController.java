package com.bkanent.compare.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.compare.model.CompareReportResponse;
import com.bkanent.compare.service.CompareAnalysisService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Listing comparison controller.
 */
@RestController
@RequestMapping("/compare")
public class CompareController {

    private final CompareAnalysisService compareAnalysisService;

    public CompareController(CompareAnalysisService compareAnalysisService) {
        this.compareAnalysisService = compareAnalysisService;
    }

    @GetMapping("/listings")
    public ApiResponse<CompareReportDTO> compare(@RequestParam List<Long> listingIds) {
        return ApiResponse.ok(compareAnalysisService.generateRpcCompareReport(listingIds));
    }

    @GetMapping("/reports")
    public ApiResponse<CompareReportResponse> report(@RequestParam List<Long> listingIds,
                                                     @RequestParam(defaultValue = "true") boolean includeAiConclusion) {
        return ApiResponse.ok(compareAnalysisService.generateCompareReport(listingIds, includeAiConclusion));
    }

    @GetMapping("/reports/share/{shareCode}")
    public ApiResponse<CompareReportResponse> sharedReport(@PathVariable String shareCode) {
        return ApiResponse.ok(compareAnalysisService.getSharedReport(shareCode));
    }

    @GetMapping("/reports/pdf/{shareCode}")
    public ResponseEntity<Resource> downloadPdf(@PathVariable String shareCode) {
        Resource resource = compareAnalysisService.loadPdfResource(shareCode);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"compare-report-" + shareCode + ".pdf\"")
                .body(resource);
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("compare-engine-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
