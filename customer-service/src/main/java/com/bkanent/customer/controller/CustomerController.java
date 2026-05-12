package com.bkanent.customer.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.common.model.CustomerProfileDTO;
import com.bkanent.common.model.HealthStatusDTO;
import com.bkanent.customer.model.CustomerDetailResponse;
import com.bkanent.customer.model.CustomerFavoriteRequest;
import com.bkanent.customer.model.CustomerFavoriteResponse;
import com.bkanent.customer.model.CustomerFollowRecordRequest;
import com.bkanent.customer.model.CustomerFollowRecordResponse;
import com.bkanent.customer.model.CustomerMatchResponse;
import com.bkanent.customer.model.CustomerQueryRequest;
import com.bkanent.customer.model.CustomerUpsertRequest;
import com.bkanent.customer.model.OwnerEntrustResponse;
import com.bkanent.customer.model.OwnerEntrustUpsertRequest;
import com.bkanent.customer.service.CustomerManagementService;
import com.bkanent.customer.service.CustomerReminderService;
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
 * Customer profile controller.
 */
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerManagementService customerManagementService;
    private final CustomerReminderService customerReminderService;

    public CustomerController(CustomerManagementService customerManagementService,
                              CustomerReminderService customerReminderService) {
        this.customerManagementService = customerManagementService;
        this.customerReminderService = customerReminderService;
    }

    @PostMapping
    public ApiResponse<CustomerProfileDTO> create(@RequestBody CustomerUpsertRequest request) {
        return ApiResponse.ok(customerManagementService.createProfile(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CustomerProfileDTO> update(@PathVariable Long id, @RequestBody CustomerUpsertRequest request) {
        return ApiResponse.ok(customerManagementService.updateProfile(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        customerManagementService.deleteProfile(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}")
    public ApiResponse<CustomerDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(customerManagementService.getCustomerDetail(id));
    }

    @GetMapping
    public ApiResponse<List<CustomerProfileDTO>> search(@RequestParam(required = false) String profileType,
                                                        @RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) Long brokerId,
                                                        @RequestParam(required = false) String intention,
                                                        @RequestParam(required = false) BigDecimal budgetMin,
                                                        @RequestParam(required = false) BigDecimal budgetMax) {
        return ApiResponse.ok(customerManagementService.searchProfiles(new CustomerQueryRequest(
                profileType,
                keyword,
                brokerId,
                intention,
                budgetMin,
                budgetMax
        )));
    }

    @PostMapping("/{id}/follow-records")
    public ApiResponse<CustomerFollowRecordResponse> addFollowRecord(@PathVariable Long id,
                                                                     @RequestBody CustomerFollowRecordRequest request) {
        return ApiResponse.ok(customerManagementService.addFollowRecord(id, request));
    }

    @GetMapping("/{id}/follow-records")
    public ApiResponse<List<CustomerFollowRecordResponse>> listFollowRecords(@PathVariable Long id) {
        return ApiResponse.ok(customerManagementService.listFollowRecords(id));
    }

    @GetMapping("/{id}/matched-listings")
    public ApiResponse<List<CustomerMatchResponse>> matchedListings(@PathVariable Long id) {
        return ApiResponse.ok(customerManagementService.matchListings(id));
    }

    @PostMapping("/{id}/entrusts")
    public ApiResponse<OwnerEntrustResponse> saveEntrust(@PathVariable Long id,
                                                         @RequestBody OwnerEntrustUpsertRequest request) {
        return ApiResponse.ok(customerManagementService.saveEntrust(id, request));
    }

    @GetMapping("/{id}/entrusts")
    public ApiResponse<List<OwnerEntrustResponse>> listEntrusts(@PathVariable Long id) {
        return ApiResponse.ok(customerManagementService.listEntrusts(id));
    }

    @GetMapping("/entrusts/expiring")
    public ApiResponse<List<OwnerEntrustResponse>> expiringEntrusts(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(customerManagementService.listExpiringEntrusts(days));
    }

    @PostMapping("/entrusts/remind")
    public ApiResponse<Integer> remindExpiringEntrusts(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(customerReminderService.sendEntrustExpiryReminders(days));
    }

    @PostMapping("/{id}/favorites")
    public ApiResponse<CustomerFavoriteResponse> addFavorite(@PathVariable Long id,
                                                             @RequestBody CustomerFavoriteRequest request) {
        return ApiResponse.ok(customerManagementService.addFavorite(id, request));
    }

    @DeleteMapping("/{id}/favorites/{listingId}")
    public ApiResponse<Void> removeFavorite(@PathVariable Long id, @PathVariable Long listingId) {
        customerManagementService.removeFavorite(id, listingId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/favorites")
    public ApiResponse<List<CustomerFavoriteResponse>> listFavorites(@PathVariable Long id) {
        return ApiResponse.ok(customerManagementService.listFavorites(id));
    }

    @GetMapping("/health")
    public ApiResponse<HealthStatusDTO> health() {
        return ApiResponse.ok(new HealthStatusDTO("customer-service", "UP", "1.0.0-SNAPSHOT"));
    }
}
