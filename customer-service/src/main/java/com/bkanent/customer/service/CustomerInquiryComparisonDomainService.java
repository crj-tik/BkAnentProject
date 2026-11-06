package com.bkanent.customer.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.bkanent.customer.entity.CustomerInquiryComparisonEntity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 买方咨询 × 对比报告关联领域服务接口。
 * 只维护单向引用（reportId + 元数据），不读取、缓存或转存报告内容（设计决策 2）。
 */
public interface CustomerInquiryComparisonDomainService extends IService<CustomerInquiryComparisonEntity> {

    /**
     * 业务方法：listByInquiry（按关联时间升序）。
     */
    List<CustomerInquiryComparisonEntity> listByInquiry(Long inquiryId);

    /**
     * 业务方法：listByInquiryIds（批量读取，按咨询 ID + 关联时间升序，供列表填充）。
     */
    List<CustomerInquiryComparisonEntity> listByInquiryIds(Collection<Long> inquiryIds);

    /**
     * 业务方法：find（幂等探测：同 (咨询, 报告) 是否已存在）。
     */
    Optional<CustomerInquiryComparisonEntity> find(Long inquiryId, String reportId);

    /**
     * 业务方法：insert（插入关联，created_by=操作人）。
     */
    CustomerInquiryComparisonEntity insert(Long inquiryId, String reportId, long createdBy);
}
