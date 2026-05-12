package com.bkanent.compare.service;

import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.compare.model.CompareReportResponse;
import org.springframework.core.io.Resource;

import java.util.List;

/**
 * 房源对比分析服务接口。
 */
public interface CompareAnalysisService {

    /**
     * 业务方法：generateCompareReport。
     */
    CompareReportResponse generateCompareReport(List<Long> listingIds, boolean includeAiConclusion);

    /**
     * 业务方法：generateRpcCompareReport。
     */
    CompareReportDTO generateRpcCompareReport(List<Long> listingIds);

    /**
     * 业务方法：getSharedReport。
     */
    CompareReportResponse getSharedReport(String shareCode);

    /**
     * 业务方法：loadPdfResource。
     */
    Resource loadPdfResource(String shareCode);
}
