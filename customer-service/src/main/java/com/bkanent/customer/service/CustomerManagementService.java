package com.bkanent.customer.service;

import com.bkanent.common.model.CustomerProfileDTO;
import com.bkanent.customer.model.CustomerDetailResponse;
import com.bkanent.customer.model.CustomerFavoriteRequest;
import com.bkanent.customer.model.CustomerFavoriteResponse;
import com.bkanent.customer.model.CustomerFollowRecordRequest;
import com.bkanent.customer.model.CustomerFollowRecordResponse;
import com.bkanent.customer.model.CustomerMatchResponse;
import com.bkanent.customer.model.CustomerQueryRequest;
import com.bkanent.customer.model.CustomerUpsertRequest;
import com.bkanent.customer.model.OwnerEntrustResponse;
import com.bkanent.customer.model.OwnerEntrustUpsertRequest;

import java.util.List;

/**
 * 客源客户管理服务接口。
 */
public interface CustomerManagementService {

    /**
     * 业务方法：createProfile。
     */
    CustomerProfileDTO createProfile(CustomerUpsertRequest request);

    /**
     * 业务方法：updateProfile。
     */
    CustomerProfileDTO updateProfile(Long customerId, CustomerUpsertRequest request);

    /**
     * 业务方法：deleteProfile。
     */
    void deleteProfile(Long customerId);

    /**
     * 业务方法：getCustomerDetail。
     */
    CustomerDetailResponse getCustomerDetail(Long customerId);

    /**
     * 业务方法：searchProfiles。
     */
    List<CustomerProfileDTO> searchProfiles(CustomerQueryRequest request);

    /**
     * 业务方法：addFollowRecord。
     */
    CustomerFollowRecordResponse addFollowRecord(Long customerId, CustomerFollowRecordRequest request);

    /**
     * 业务方法：listFollowRecords。
     */
    List<CustomerFollowRecordResponse> listFollowRecords(Long customerId);

    /**
     * 业务方法：matchListings。
     */
    List<CustomerMatchResponse> matchListings(Long customerId);

    /**
     * 业务方法：saveEntrust。
     */
    OwnerEntrustResponse saveEntrust(Long customerId, OwnerEntrustUpsertRequest request);

    /**
     * 业务方法：listEntrusts。
     */
    List<OwnerEntrustResponse> listEntrusts(Long customerId);

    /**
     * 业务方法：listExpiringEntrusts。
     */
    List<OwnerEntrustResponse> listExpiringEntrusts(int days);

    /**
     * 业务方法：addFavorite。
     */
    CustomerFavoriteResponse addFavorite(Long customerId, CustomerFavoriteRequest request);

    /**
     * 业务方法：removeFavorite。
     */
    void removeFavorite(Long customerId, Long listingId);

    /**
     * 业务方法：listFavorites。
     */
    List<CustomerFavoriteResponse> listFavorites(Long customerId);
}
