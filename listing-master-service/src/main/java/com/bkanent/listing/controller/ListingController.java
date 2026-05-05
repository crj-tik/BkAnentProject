package com.bkanent.listing.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/listings")
public class ListingController {

    private final ListingMasterRpcService listingMasterRpcService;

    public ListingController(ListingMasterRpcService listingMasterRpcService) {
        this.listingMasterRpcService = listingMasterRpcService;
    }

    @GetMapping("/{id}")
    public ApiResponse<ListingDTO> detail(@PathVariable Long id) {
        return ApiResponse.ok(listingMasterRpcService.getListingById(id));
    }

    @GetMapping
    public ApiResponse<List<ListingDTO>> search(@RequestParam(required = false, defaultValue = "") String keyword) {
        return ApiResponse.ok(listingMasterRpcService.searchListings(keyword));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("listing-master-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
