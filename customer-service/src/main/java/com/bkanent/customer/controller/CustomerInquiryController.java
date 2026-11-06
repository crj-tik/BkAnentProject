package com.bkanent.customer.controller;

import com.bkanent.common.model.ApiResponse;
import com.bkanent.customer.model.CustomerInquiryComparisonAttachRequest;
import com.bkanent.customer.model.CustomerInquiryComparisonView;
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
 * 买方房源咨询端点：显式登记、列表、业务状态变更与对比报告关联。
 * 咨询与收藏分离；查询不创建咨询记录；关联只存 reportId 引用，不转存报告内容。
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

    @PostMapping("/inquiries/{inquiryId}/comparison-reports")
    public ApiResponse<CustomerInquiryComparisonView> attachComparisonReport(
            @PathVariable Long inquiryId, @RequestBody CustomerInquiryComparisonAttachRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        return ApiResponse.ok(inquiryService.attachComparisonReport(inquiryId, request.reportId()));
    }
}
