package com.bkanent.common.rpc;

import com.bkanent.common.model.ListingDTO;

import java.util.List;

/**
 * ListingMasterRpcService 服务接口。
 */

public interface ListingMasterRpcService {

    /**
     * 业务方法：getListingById。
     */
    ListingDTO getListingById(Long listingId);

    /**
     * 业务方法：searchListings。
     */
    List<ListingDTO> searchListings(String keyword);
}

