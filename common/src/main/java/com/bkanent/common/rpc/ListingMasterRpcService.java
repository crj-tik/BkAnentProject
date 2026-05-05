package com.bkanent.common.rpc;

import com.bkanent.common.model.ListingDTO;

import java.util.List;

public interface ListingMasterRpcService {

    ListingDTO getListingById(Long listingId);

    List<ListingDTO> searchListings(String keyword);
}
