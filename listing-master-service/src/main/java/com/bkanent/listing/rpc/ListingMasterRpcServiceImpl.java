package com.bkanent.listing.rpc;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import com.bkanent.listing.service.ListingManagementService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * ListingMasterRpcServiceImpl RPC 服务实现类。
 */
@DubboService
public class ListingMasterRpcServiceImpl implements ListingMasterRpcService {

    private final ListingManagementService listingManagementService;

    public ListingMasterRpcServiceImpl(ListingManagementService listingManagementService) {
        this.listingManagementService = listingManagementService;
    }

    @Override
    public ListingDTO getListingById(Long listingId) {
        return listingManagementService.getListingSummary(listingId);
    }

    @Override
    public List<ListingDTO> searchListings(String keyword) {
        return listingManagementService.searchListingSummaries(keyword);
    }
}
