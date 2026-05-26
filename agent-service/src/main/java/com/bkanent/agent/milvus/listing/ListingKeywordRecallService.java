package com.bkanent.agent.milvus.listing;

import com.bkanent.agent.model.rag.ListingRagQueryRequest;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * ListingKeywordRecallService 服务类。
 */
@Service
public class ListingKeywordRecallService {

    /**
     * 字段：listingMasterRpcService。
     */
    private final ListingMasterRpcService listingMasterRpcService;

    /**
     * 构造 ListingKeywordRecallService 实例。
     */
    public ListingKeywordRecallService(ListingMasterRpcService listingMasterRpcService) {
        this.listingMasterRpcService = listingMasterRpcService;
    }

    /**
     * 调用 ES BM25 关键词召回。
     */
    public List<ListingKeywordSearchResultDTO> recall(ListingRagQueryRequest request, int topK) {
        if (!StringUtils.hasText(request.query())) {
            return List.of();
        }
        return listingMasterRpcService.searchListingsByKeyword(new ListingKeywordSearchRequest(
                request.query(),
                request.region(),
                request.layout(),
                request.minArea(),
                request.maxArea(),
                request.minTotalPrice(),
                request.maxTotalPrice(),
                Math.max(1, topK)
        )).stream()
                .filter(result -> result.listing() != null)
                .toList();
    }
}
