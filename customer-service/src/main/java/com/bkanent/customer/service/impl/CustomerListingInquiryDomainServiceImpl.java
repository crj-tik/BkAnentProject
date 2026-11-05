package com.bkanent.customer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.customer.entity.CustomerListingInquiryEntity;
import com.bkanent.customer.mapper.CustomerListingInquiryMapper;
import com.bkanent.customer.service.CustomerListingInquiryDomainService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 买方房源咨询领域服务实现。
 */
@Service
public class CustomerListingInquiryDomainServiceImpl
        extends ServiceImpl<CustomerListingInquiryMapper, CustomerListingInquiryEntity>
        implements CustomerListingInquiryDomainService {

    @Override
    public List<CustomerListingInquiryEntity> listByCustomer(Long customerId) {
        return list(new LambdaQueryWrapper<CustomerListingInquiryEntity>()
                .eq(CustomerListingInquiryEntity::getCustomerId, customerId)
                .orderByDesc(CustomerListingInquiryEntity::getCreatedAt)
                .orderByDesc(CustomerListingInquiryEntity::getId));
    }

    @Override
    public List<CustomerListingInquiryEntity> listByListing(Long listingId) {
        return list(new LambdaQueryWrapper<CustomerListingInquiryEntity>()
                .eq(CustomerListingInquiryEntity::getListingId, listingId)
                .orderByDesc(CustomerListingInquiryEntity::getCreatedAt)
                .orderByDesc(CustomerListingInquiryEntity::getId));
    }

    @Override
    public List<CustomerListingInquiryEntity> listByBroker(Long brokerId) {
        return list(new LambdaQueryWrapper<CustomerListingInquiryEntity>()
                .eq(CustomerListingInquiryEntity::getBrokerId, brokerId)
                .orderByDesc(CustomerListingInquiryEntity::getUpdatedAt)
                .orderByDesc(CustomerListingInquiryEntity::getId));
    }

    @Override
    public List<CustomerListingInquiryEntity> listByValidationState(String validationState) {
        return list(new LambdaQueryWrapper<CustomerListingInquiryEntity>()
                .eq(CustomerListingInquiryEntity::getValidationState, validationState)
                .orderByAsc(CustomerListingInquiryEntity::getUpdatedAt)
                .orderByAsc(CustomerListingInquiryEntity::getId));
    }
}
