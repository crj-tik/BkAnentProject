package com.bkanent.listing.controller;

import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.listing.ListingComparisonCreateRequest;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.listing.comparison.ListingComparisonException;
import com.bkanent.listing.comparison.ListingComparisonService;
import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;
import com.bkanent.listing.source.ListingBusinessApiException;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/listings/comparisons")
public class ListingComparisonController {
    private final ListingComparisonService service;

    public ListingComparisonController(ListingComparisonService service) { this.service = service; }

    @PostMapping
    public ApiResponse<ListingComparisonReportResponse> create(@RequestBody ListingComparisonCreateRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @GetMapping("/{reportId}")
    public ApiResponse<ListingComparisonReportResponse> get(@PathVariable String reportId) {
        return ApiResponse.ok(service.getOwned(reportId));
    }

    @GetMapping("/{reportId}/pdf")
    public ResponseEntity<Resource> pdf(@PathVariable String reportId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"listing-comparison-" + reportId + ".pdf\"")
                .body(service.loadPdf(reportId));
    }

    @ExceptionHandler(ListingBusinessApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleSource(ListingBusinessApiException failure) {
        return ResponseEntity.status(failure.status()).body(ApiResponse.fail(failure.code(), "房源来源请求未完成"));
    }

    @ExceptionHandler(ListingComparisonException.class)
    public ResponseEntity<ApiResponse<Void>> handleComparison(ListingComparisonException failure) {
        return ResponseEntity.status(failure.status()).body(ApiResponse.fail(failure.code(), "房源比较请求未完成"));
    }

    @ExceptionHandler(AuthzException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthorization(AuthzException failure) {
        HttpStatus status = HttpStatus.valueOf(failure.code().httpStatus());
        return ResponseEntity.status(status).body(ApiResponse.fail(failure.code().name(), "当前身份无权读取房源比较"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception failure) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_ARGUMENT", "请求格式无效"));
    }
}
