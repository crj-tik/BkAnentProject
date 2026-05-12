package com.bkanent.listing.service;

import com.bkanent.common.model.ListingDTO;
import com.bkanent.listing.model.ListingAssetBindRequest;
import com.bkanent.listing.model.ListingDetailResponse;
import com.bkanent.listing.model.ListingOcrRecognizeRequest;
import com.bkanent.listing.model.ListingQueryRequest;
import com.bkanent.listing.model.ListingStatusUpdateRequest;
import com.bkanent.listing.model.ListingUpsertRequest;
import com.bkanent.listing.model.ListingVerifyRequest;

import java.util.List;

/**
 * ListingManagementService 房源管理服务接口。
 */
public interface ListingManagementService {

    /**
     * 业务方法：createListing。
     */
    ListingDetailResponse createListing(ListingUpsertRequest request);

    /**
     * 业务方法：updateListing。
     */
    ListingDetailResponse updateListing(Long listingId, ListingUpsertRequest request);

    /**
     * 业务方法：deleteListing。
     */
    void deleteListing(Long listingId);

    /**
     * 业务方法：getListingDetail。
     */
    ListingDetailResponse getListingDetail(Long listingId);

    /**
     * 业务方法：searchListings。
     */
    List<ListingDetailResponse> searchListings(ListingQueryRequest request);

    /**
     * 业务方法：searchListingSummaries。
     */
    List<ListingDTO> searchListingSummaries(String keyword);

    /**
     * 业务方法：getListingSummary。
     */
    ListingDTO getListingSummary(Long listingId);

    /**
     * 业务方法：updateStatus。
     */
    ListingDetailResponse updateStatus(Long listingId, ListingStatusUpdateRequest request);

    /**
     * 业务方法：bindAssets。
     */
    ListingDetailResponse bindAssets(Long listingId, ListingAssetBindRequest request);

    /**
     * 业务方法：recognizeOcr。
     */
    ListingDetailResponse recognizeOcr(Long listingId, ListingOcrRecognizeRequest request);

    /**
     * 业务方法：verifyAuthenticity。
     */
    ListingDetailResponse verifyAuthenticity(Long listingId, ListingVerifyRequest request);
}
