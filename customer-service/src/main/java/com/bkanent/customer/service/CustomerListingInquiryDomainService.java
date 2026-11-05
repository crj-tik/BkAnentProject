package com.bkanent.customer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.customer.entity.CustomerListingInquiryEntity;

import java.util.List;

/**
 * 买方房源咨询领域服务接口。
 */
public interface CustomerListingInquiryDomainService extends IService<CustomerListingInquiryEntity> {

    /**
     * 业务方法：listByCustomer。
     */
    List<CustomerListingInquiryEntity> listByCustomer(Long customerId);

    /**
     * 业务方法：listByListing。
     */
    List<CustomerListingInquiryEntity> listByListing(Long listingId);

    /**
     * 业务方法：listByBroker。
     */
    List<CustomerListingInquiryEntity> listByBroker(Long brokerId);

    /**
     * 业务方法：listByValidationState。
     */
    List<CustomerListingInquiryEntity> listByValidationState(String validationState);
}
