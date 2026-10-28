package com.bkanent.listing.controller;

import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingSnapshotBatchReadItem;
import com.bkanent.common.listing.ListingSnapshotBatchReadRequest;
import com.bkanent.common.listing.ListingSnapshotCheckItem;
import com.bkanent.common.listing.ListingSnapshotCheckRequest;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.model.ApiResponse;
import com.bkanent.listing.source.ListingBusinessApiException;
import com.bkanent.listing.source.ListingBusinessSourceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/listings/business/v1")
public class ListingBusinessSourceController {
    private final ListingBusinessSourceService service;

    public ListingBusinessSourceController(ListingBusinessSourceService service) {
        this.service = service;
    }

    @PostMapping("/search")
    public ApiResponse<ListingBusinessResponse<ListingSourceSnapshot>> search(
            @RequestBody ListingBusinessSearchRequest request) {
        return ApiResponse.ok(service.search(request));
    }

    @PostMapping("/snapshots/batch-read")
    public ApiResponse<ListingBusinessResponse<ListingSnapshotBatchReadItem>> batchRead(
            @RequestBody ListingSnapshotBatchReadRequest request) {
        return ApiResponse.ok(service.batchRead(request));
    }

    @PostMapping("/snapshots/check")
    public ApiResponse<ListingBusinessResponse<ListingSnapshotCheckItem>> check(
            @RequestBody ListingSnapshotCheckRequest request) {
        return ApiResponse.ok(service.check(request));
    }

    @ExceptionHandler(ListingBusinessApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleSourceError(ListingBusinessApiException failure) {
        return ResponseEntity.status(failure.status()).body(ApiResponse.fail(failure.code(), "房源来源请求未完成"));
    }

    @ExceptionHandler(AuthzException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthorization(AuthzException failure) {
        HttpStatus status = HttpStatus.valueOf(failure.code().httpStatus());
        return ResponseEntity.status(status).body(ApiResponse.fail(failure.code().name(), "当前身份无权读取房源"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception failure) {
        return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_ARGUMENT", "请求格式无效"));
    }
}
