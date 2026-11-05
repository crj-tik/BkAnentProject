package com.bkanent.customer.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.customer.model.CustomerInquiryCreateRequest;
import com.bkanent.customer.model.CustomerInquiryResponse;
import com.bkanent.customer.model.CustomerInquiryStatusUpdateRequest;
import com.bkanent.customer.service.CustomerListingInquiryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买方房源咨询端点：显式登记、列表与业务状态变更。
 * 咨询与收藏分离；查询不创建咨询记录。
 */
@RestController
@RequestMapping("/customers")
public class CustomerInquiryController {

    private final CustomerListingInquiryService inquiryService;

    public CustomerInquiryController(CustomerListingInquiryService inquiryService) {
        this.inquiryService = inquiryService;
    }

    @PostMapping("/{id}/inquiries")
    public ApiResponse<CustomerInquiryResponse> createInquiry(@PathVariable Long id,
                                                              @RequestBody CustomerInquiryCreateRequest request) {
        return ApiResponse.ok(inquiryService.createInquiry(id, request));
    }

    @GetMapping("/{id}/inquiries")
    public ApiResponse<List<CustomerInquiryResponse>> listInquiries(@PathVariable Long id) {
        return ApiResponse.ok(inquiryService.listInquiries(id));
    }

    @PutMapping("/inquiries/{inquiryId}/status")
    public ApiResponse<CustomerInquiryResponse> updateInquiryStatus(
            @PathVariable Long inquiryId, @RequestBody CustomerInquiryStatusUpdateRequest request) {
        return ApiResponse.ok(inquiryService.updateInquiryStatus(inquiryId, request.status()));
    }
}
