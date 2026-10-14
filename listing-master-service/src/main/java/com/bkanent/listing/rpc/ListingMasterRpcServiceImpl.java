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
    private final com.bkanent.listing.rag.ListingRagIdentity identity;
    private final com.bkanent.listing.service.ListingDomainService domain;
    private final com.bkanent.listing.converter.ListingConverter converter;

    /**
     * 构造 ListingMasterRpcServiceImpl 实例。
     */
    public ListingMasterRpcServiceImpl(ListingManagementService listingManagementService,
                                        com.bkanent.listing.rag.ListingRagIdentity identity,
                                        com.bkanent.listing.service.ListingDomainService domain,
                                        com.bkanent.listing.converter.ListingConverter converter) {
        this.listingManagementService = listingManagementService;
        this.identity = identity;
        this.domain = domain;
        this.converter = converter;
    }

    @Override
    public List<ListingDTO> read(com.bkanent.common.rag.RagListingReadRequest request,
                                com.bkanent.common.rag.RagAuthEnvelope authentication,
                                com.bkanent.common.rag.RagCredentialContext credentials) {
        identity.verifyCaller(request, request.requestId(), com.bkanent.common.rag.RagOperation.READ, authentication, credentials);
        if (authentication.actorMode() == com.bkanent.common.rag.RagActorMode.USER) {
            var principal = identity.requireReader(credentials);
            if (!principal.userId().toString().equals(authentication.actorId())) {
                throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.AUTHENTICATION_FAILED);
            }
            return com.bkanent.listing.rag.ListingRagCredentialContext.withCredential(credentials, () -> {
                if (request.listingId() == null) return listingManagementService.searchListingSummaries(request.keyword());
                var dto = listingManagementService.getListingSummary(request.listingId());
                return dto == null ? List.of() : List.of(dto);
            });
        }
        // No production background READ caller exists: missing USER context cannot select SERVICE.
        throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.FORBIDDEN);
    }

    /**
     * 获取指定房源摘要。
     */
    @Override
    public ListingDTO getListingById(Long listingId) {
        throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.AUTHENTICATION_FAILED);
    }

    /**
     * 查询房源摘要列表。
     */
    @Override
    public List<ListingDTO> searchListings(String keyword) {
        throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.AUTHENTICATION_FAILED);
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
        throw new com.bkanent.common.rag.RagException(com.bkanent.common.rag.RagErrorCode.AUTHENTICATION_FAILED);
    }
}
