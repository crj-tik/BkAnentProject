package com.bkanent.common.rpc;

import com.bkanent.common.model.CompareReportDTO;

import java.util.List;

/**
 * CompareEngineRpcService 服务接口。
 */

public interface CompareEngineRpcService {

    /**
     * 业务方法：compareListings。
     */
    CompareReportDTO compareListings(List<Long> listingIds);
}

