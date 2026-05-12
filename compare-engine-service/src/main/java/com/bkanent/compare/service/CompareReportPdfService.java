package com.bkanent.compare.service;

import com.bkanent.compare.model.CompareReportResponse;

/**
 * 房源对比 PDF 生成服务接口。
 */
public interface CompareReportPdfService {

    /**
     * 业务方法：generatePdf。
     */
    byte[] generatePdf(CompareReportResponse report);
}
