package com.bkanent.listing.service;

import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.listing.entity.ListingEntity;

import java.util.List;

/**
 * ListingSearchService 服务接口定义。
 */
public interface ListingSearchService {

    /**
     * 写入或更新房源检索文档。
     */
    void indexListing(ListingEntity entity);

    /**
     * 删除房源检索文档。
     */
    void deleteListing(Long listingId);

    /**
     * 使用关键词检索房源候选。
     */
    List<ListingKeywordSearchResultDTO> searchByKeyword(ListingKeywordSearchRequest request);
}
