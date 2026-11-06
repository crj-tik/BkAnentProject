package com.bkanent.customer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.bkanent.customer.entity.CustomerInquiryComparisonEntity;
import com.bkanent.customer.mapper.CustomerInquiryComparisonMapper;
import com.bkanent.customer.service.CustomerInquiryComparisonDomainService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 买方咨询 × 对比报告关联领域服务实现。
 * created_at 由服务端显式赋值（表虽带 CURRENT_TIMESTAMP 默认值，显式赋值保证响应即时回带关联时间）。
 */
@Service
public class CustomerInquiryComparisonDomainServiceImpl
        extends ServiceImpl<CustomerInquiryComparisonMapper, CustomerInquiryComparisonEntity>
        implements CustomerInquiryComparisonDomainService {

    @Override
    public List<CustomerInquiryComparisonEntity> listByInquiry(Long inquiryId) {
        if (inquiryId == null || inquiryId <= 0) {
            return List.of();
        }
        return list(new LambdaQueryWrapper<CustomerInquiryComparisonEntity>()
                .eq(CustomerInquiryComparisonEntity::getInquiryId, inquiryId)
                .orderByAsc(CustomerInquiryComparisonEntity::getCreatedAt)
                .orderByAsc(CustomerInquiryComparisonEntity::getId));
    }

    @Override
    public List<CustomerInquiryComparisonEntity> listByInquiryIds(Collection<Long> inquiryIds) {
        if (inquiryIds == null || inquiryIds.isEmpty()) {
            return List.of();
        }
        return list(new LambdaQueryWrapper<CustomerInquiryComparisonEntity>()
                .in(CustomerInquiryComparisonEntity::getInquiryId, inquiryIds)
                .orderByAsc(CustomerInquiryComparisonEntity::getInquiryId)
                .orderByAsc(CustomerInquiryComparisonEntity::getCreatedAt)
                .orderByAsc(CustomerInquiryComparisonEntity::getId));
    }

    @Override
    public Optional<CustomerInquiryComparisonEntity> find(Long inquiryId, String reportId) {
        if (inquiryId == null || inquiryId <= 0 || reportId == null || reportId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(getOne(new LambdaQueryWrapper<CustomerInquiryComparisonEntity>()
                .eq(CustomerInquiryComparisonEntity::getInquiryId, inquiryId)
                .eq(CustomerInquiryComparisonEntity::getReportId, reportId)
                .last("LIMIT 1"), false));
    }

    @Override
    public CustomerInquiryComparisonEntity insert(Long inquiryId, String reportId, long createdBy) {
        CustomerInquiryComparisonEntity entity = new CustomerInquiryComparisonEntity();
        entity.setInquiryId(inquiryId);
        entity.setReportId(reportId);
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(LocalDateTime.now());
        save(entity);
        return entity;
    }
}
