package com.bkanent.compare.service;

import com.bkanent.common.model.CompareReportDTO;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rpc.CompareEngineRpcService;
import com.bkanent.common.rpc.ListingMasterRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
public class CompareEngineRpcServiceImpl implements CompareEngineRpcService {

    @DubboReference(check = false)
    private ListingMasterRpcService listingMasterRpcService;

    @Override
    public CompareReportDTO compareListings(List<Long> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) {
            return new CompareReportDTO(List.of(), "| 房源 | 单价 |\n| --- | --- |", "未传入对比房源");
        }
        List<ListingDTO> listings = listingMasterRpcService == null
                ? List.of()
                : listingIds.stream().map(listingMasterRpcService::getListingById).toList();
        return new CompareReportDTO(listings, "| 房源 | 单价 |\n| --- | --- |", "待接入大模型生成差异分析");
    }
}
