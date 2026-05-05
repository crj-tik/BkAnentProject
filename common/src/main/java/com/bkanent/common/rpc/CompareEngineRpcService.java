package com.bkanent.common.rpc;

import com.bkanent.common.model.CompareReportDTO;

import java.util.List;

public interface CompareEngineRpcService {

    CompareReportDTO compareListings(List<Long> listingIds);
}
