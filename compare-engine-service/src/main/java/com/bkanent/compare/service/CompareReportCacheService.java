package com.bkanent.compare.service;

import com.bkanent.compare.model.CompareReportResponse;
import com.bkanent.compare.model.CompareReportCacheEntry;
import org.springframework.core.io.Resource;

import java.util.Optional;

/**
 * 房源对比报告缓存服务接口。
 */
public interface CompareReportCacheService {

    /**
     * 业务方法：findByCacheKey。
     */
    Optional<CompareReportResponse> findByCacheKey(String ownerActorId, String cacheKey);

    /**
     * 业务方法：save。
     */
    CompareReportResponse save(String ownerActorId, String cacheKey, String sourceVersionHash,
                               CompareReportResponse report);

    /**
     * 业务方法：getByShareCode。
     */
    CompareReportCacheEntry findEntryByShareCode(String shareCode);

    CompareReportCacheEntry publishShare(String shareCode, String ownerActorId);

    /**
     * 业务方法：loadPdfResource。
     */
    Resource loadPdfResource(String shareCode);
}
