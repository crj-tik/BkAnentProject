package com.bkanent.common.rpc;

import com.bkanent.common.model.KnowledgeDocument;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;

import java.util.List;

/**
 * ListingMasterRpcService 服务接口。
 */
public interface ListingMasterRpcService {

    /**
     * 获取指定房源摘要。
     */
    ListingDTO getListingById(Long listingId);

    /**
     * 查询房源摘要列表。
     */
    List<ListingDTO> searchListings(String keyword);

    /**
     * 使用 ES BM25 检索房源候选。
     */
    List<ListingKeywordSearchResultDTO> searchListingsByKeyword(ListingKeywordSearchRequest request);

    /**
     * 获取房源知识文档。
     */
    KnowledgeDocument getListingKnowledgeDocument(Long listingId);
}
