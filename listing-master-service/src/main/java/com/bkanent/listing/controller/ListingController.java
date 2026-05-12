package com.bkanent.listing.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.listing.model.ListingAssetBindRequest;
import com.bkanent.listing.model.ListingDetailResponse;
import com.bkanent.listing.model.ListingOcrRecognizeRequest;
import com.bkanent.listing.model.ListingQueryRequest;
import com.bkanent.listing.model.ListingStatusUpdateRequest;
import com.bkanent.listing.model.ListingUpsertRequest;
import com.bkanent.listing.model.ListingVerifyRequest;
import com.bkanent.listing.service.ListingManagementService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Listing management controller.
 */
@RestController
@RequestMapping("/listings")
public class ListingController {

    private final ListingManagementService listingManagementService;

    public ListingController(ListingManagementService listingManagementService) {
        this.listingManagementService = listingManagementService;
    }

    @PostMapping
    public ApiResponse<ListingDetailResponse> create(@RequestBody ListingUpsertRequest request) {
        return ApiResponse.ok(listingManagementService.createListing(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ListingDetailResponse> update(@PathVariable Long id, @RequestBody ListingUpsertRequest request) {
        return ApiResponse.ok(listingManagementService.updateListing(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        listingManagementService.deleteListing(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}")
    public ApiResponse<ListingDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(listingManagementService.getListingDetail(id));
    }

    @GetMapping("/search")
    public ApiResponse<List<ListingDetailResponse>> search(@RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(required = false) Long brokerId,
                                                           @RequestParam(required = false) BigDecimal minArea,
                                                           @RequestParam(required = false) BigDecimal maxArea,
                                                           @RequestParam(required = false) BigDecimal minTotalPrice,
                                                           @RequestParam(required = false) BigDecimal maxTotalPrice,
                                                           @RequestParam(required = false) Boolean verifiedOnly) {
        return ApiResponse.ok(listingManagementService.searchListings(new ListingQueryRequest(
                keyword,
                status,
                brokerId,
                minArea,
                maxArea,
                minTotalPrice,
                maxTotalPrice,
                verifiedOnly
        )));
    }

    @GetMapping
    public ApiResponse<List<ListingDTO>> searchSummary(@RequestParam(required = false, defaultValue = "") String keyword) {
        return ApiResponse.ok(listingManagementService.searchListingSummaries(keyword));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<ListingDetailResponse> updateStatus(@PathVariable Long id,
                                                           @RequestBody ListingStatusUpdateRequest request) {
        return ApiResponse.ok(listingManagementService.updateStatus(id, request));
    }

    @PutMapping("/{id}/assets")
    public ApiResponse<ListingDetailResponse> bindAssets(@PathVariable Long id,
                                                         @RequestBody ListingAssetBindRequest request) {
        return ApiResponse.ok(listingManagementService.bindAssets(id, request));
    }

    @PostMapping("/{id}/ocr")
    public ApiResponse<ListingDetailResponse> recognizeOcr(@PathVariable Long id,
                                                           @RequestBody ListingOcrRecognizeRequest request) {
        return ApiResponse.ok(listingManagementService.recognizeOcr(id, request));
    }

    @PostMapping("/{id}/verify")
    public ApiResponse<ListingDetailResponse> verify(@PathVariable Long id,
                                                     @RequestBody ListingVerifyRequest request) {
        return ApiResponse.ok(listingManagementService.verifyAuthenticity(id, request));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("listing-master-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
