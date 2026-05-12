package com.bkanent.compare.service;

import com.bkanent.compare.model.CompareReportResponse;
import org.springframework.core.io.Resource;

import java.util.Optional;

/**
 * 房源对比报告缓存服务接口。
 */
public interface CompareReportCacheService {

    /**
     * 业务方法：findByCacheKey。
     */
    Optional<CompareReportResponse> findByCacheKey(String cacheKey);

    /**
     * 业务方法：save。
     */
    CompareReportResponse save(String cacheKey, CompareReportResponse report);

    /**
     * 业务方法：getByShareCode。
     */
    CompareReportResponse getByShareCode(String shareCode);

    /**
     * 业务方法：loadPdfResource。
     */
    Resource loadPdfResource(String shareCode);
}
