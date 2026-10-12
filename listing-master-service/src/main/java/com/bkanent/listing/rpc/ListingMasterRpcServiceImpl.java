package com.bkanent.listing.rpc;

import com.bkanent.common.model.KnowledgeDocument;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import com.bkanent.listing.service.ListingManagementService;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * ListingMasterRpcServiceImpl RPC 服务实现类。
 */
@DubboService
public class ListingMasterRpcServiceImpl implements ListingMasterRpcService {

    /**
     * 字段：listingManagementService。
     */
    private final ListingManagementService listingManagementService;

    /**
     * 构造 ListingMasterRpcServiceImpl 实例。
     */
    public ListingMasterRpcServiceImpl(ListingManagementService listingManagementService) {
        this.listingManagementService = listingManagementService;
    }

    /**
     * 获取指定房源摘要。
     */
    @Override
    public ListingDTO getListingById(Long listingId) {
        return listingManagementService.getListingSummary(listingId);
    }

    /**
     * 查询房源摘要列表。
     */
    @Override
    public List<ListingDTO> searchListings(String keyword) {
        return listingManagementService.searchListingSummaries(keyword);
    }

    /**
     * 无专用凭据的旧检索 RPC 安全拒绝；使用 RagListingDomainRpcService。
     */
    @Override
    public List<ListingKeywordSearchResultDTO> searchListingsByKeyword(ListingKeywordSearchRequest request) {
        // This legacy RPC has no dedicated credential parameter and cannot prove authority.
        throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.AUTHENTICATION_FAILED);
    }

    /**
     * 获取房源知识文档。
     */
    @Override
    public KnowledgeDocument getListingKnowledgeDocument(Long listingId) {
        return listingManagementService.getListingKnowledgeDocument(listingId);
    }
}
