package com.bkanent.customer.service;

import com.bkanent.customer.model.CustomerInquiryCreateRequest;
import com.bkanent.customer.model.CustomerInquiryResponse;

import java.util.List;

/**
 * 买方房源咨询管理服务接口。
 */
public interface CustomerListingInquiryService {

    /**
     * 业务方法：createInquiry。
     * 获准经纪人代买方显式登记咨询；房源经 Listing 当前在售与读取权验证，
     * 依赖不可用或不可访问时失败关闭，不创建成功记录。
     */
    CustomerInquiryResponse createInquiry(Long customerId, CustomerInquiryCreateRequest request);

    /**
     * 业务方法：listInquiries。
     * 返回当前授权范围内客户的咨询列表（含 NEEDS_RECHECK 过渡状态）。
     */
    List<CustomerInquiryResponse> listInquiries(Long customerId);

    /**
     * 业务方法：updateInquiryStatus。
     * 状态机：OPEN→FOLLOWING/CLOSED、FOLLOWING→CLOSED，CLOSED 终态，非法跳转拒绝。
     */
    CustomerInquiryResponse updateInquiryStatus(Long inquiryId, String status);

    /**
     * 业务方法：recheckAfterCreate。
     * 创建事务提交后的复查：版本变化或不可访问时置 NEEDS_RECHECK；失败不影响已创建记录。
     */
    void recheckAfterCreate(Long inquiryId);

    /**
     * 业务方法：recheckInquiries。
     * 内部补偿：逐条复查 NEEDS_RECHECK 咨询，仍 ACCESSIBLE 且版本一致 → CONFIRMED，
     * 版本变化 → 更新版本并保持 NEEDS_RECHECK，NOT_ACCESSIBLE → REJECTED。
     */
    int recheckInquiries();
}
