package com.bkanent.compare.rpc;

import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.rpc.CompareEngineRpcService;
import com.bkanent.compare.service.CompareAnalysisService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * 对比引擎 RPC 服务实现。
 */
@DubboService
public class CompareEngineRpcServiceImpl implements CompareEngineRpcService {

    private final CompareAnalysisService compareAnalysisService;

    public CompareEngineRpcServiceImpl(CompareAnalysisService compareAnalysisService) {
        this.compareAnalysisService = compareAnalysisService;
    }

    @Override
    public CompareReportDTO compareListings(List<Long> listingIds) {
        return compareAnalysisService.generateRpcCompareReport(listingIds);
    }
}
