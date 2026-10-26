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

    List<ListingDTO> read(com.bkanent.common.rag.RagListingReadRequest request,
                          com.bkanent.common.rag.RagAuthEnvelope authentication,
                          com.bkanent.common.rag.RagCredentialContext credentials) throws com.bkanent.common.rag.RagException;

    /** Request-level signed Auth context plus a caller-signed proof; no user bearer is forwarded. */
    List<ListingDTO> readAuthorized(com.bkanent.common.rag.RagAuthorizedListingReadRequest request,
                                    com.bkanent.common.rag.RagAuthEnvelope authentication,
                                    com.bkanent.common.authz.AuthzResponse authorization)
            throws com.bkanent.common.rag.RagException;

    /** A2A request-level proof with the Auth-signed run context; no bearer or per-tool Auth call. */
    List<ListingDTO> readAuthorizedContext(
            com.bkanent.common.rag.RagContextAuthorizedListingReadRequest request,
            com.bkanent.common.rag.RagAuthEnvelope authentication)
            throws com.bkanent.common.rag.RagException;

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
