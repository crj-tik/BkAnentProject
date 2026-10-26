package com.bkanent.listing.rpc;

import com.bkanent.common.model.KnowledgeDocument;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.common.model.ListingKeywordSearchRequest;
import com.bkanent.common.model.ListingKeywordSearchResultDTO;
import com.bkanent.common.rpc.ListingMasterRpcService;
import com.bkanent.listing.config.ListingAuthorizationProperties;
import com.bkanent.listing.service.ListingManagementService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ListingAuthorizationProperties authorizationProperties;

    /**
     * 构造 ListingMasterRpcServiceImpl 实例。
     */
    @Autowired
    public ListingMasterRpcServiceImpl(ListingManagementService listingManagementService,
                                       com.bkanent.listing.rag.ListingRagIdentity identity,
                                       com.bkanent.listing.service.ListingDomainService domain,
                                       com.bkanent.listing.converter.ListingConverter converter,
                                       ListingAuthorizationProperties authorizationProperties) {
        this.listingManagementService = listingManagementService;
        this.identity = identity;
        this.domain = domain;
        this.converter = converter;
        this.authorizationProperties = authorizationProperties;
    }

    public ListingMasterRpcServiceImpl(ListingManagementService listingManagementService,
                                       com.bkanent.listing.rag.ListingRagIdentity identity,
                                       com.bkanent.listing.service.ListingDomainService domain,
                                       com.bkanent.listing.converter.ListingConverter converter) {
        this(listingManagementService, identity, domain, converter, new ListingAuthorizationProperties());
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

    @Override
    public List<ListingDTO> readAuthorized(com.bkanent.common.rag.RagAuthorizedListingReadRequest request,
                                           com.bkanent.common.rag.RagAuthEnvelope authentication,
                                           com.bkanent.common.authz.AuthzResponse authorization) {
        var scope = identity.openAuthorizedReadScope(request, authentication, authorization,
                authorizationProperties.decodedAuthorityPublicKeys());
        try (scope) {
            var read = request.request();
            if (read.listingId() == null) return listingManagementService.searchListingSummaries(read.keyword());
            var dto = listingManagementService.getListingSummary(read.listingId());
            return dto == null ? List.of() : List.of(dto);
        }
    }

    @Override
    public List<ListingDTO> readAuthorizedContext(
            com.bkanent.common.rag.RagContextAuthorizedListingReadRequest request,
            com.bkanent.common.rag.RagAuthEnvelope authentication) {
        if (!authorizationProperties.isEnabled()) {
            throw new com.bkanent.common.rag.RagException(
                    com.bkanent.common.rag.RagErrorCode.AUTHENTICATION_FAILED);
        }
        var principal = identity.verifyAuthorizedReadContext(request, authentication,
                authorizationProperties.decodedAuthorityPublicKeys());
        var listing = domain.getById(request.request().listingId());
        if (!com.bkanent.listing.rag.ListingRagIdentity.canRead(principal, listing)) return List.of();
        return List.of(converter.toDto(listing));
    }

    @Override
    public boolean authorizeAssetWrite(
            com.bkanent.common.rag.RagAuthorizedListingAssetWriteRequest request,
            com.bkanent.common.rag.RagAuthEnvelope authentication,
            com.bkanent.common.authz.AuthzResponse authorization) {
        var scope = identity.openAuthorizedAssetWriteScope(request, authentication, authorization,
                authorizationProperties.decodedAuthorityPublicKeys());
        try (scope) {
            boolean businessWide = com.bkanent.common.authz.AuthzRequestScope.businessAdmin();
            long brokerId = businessWide ? 0L
                    : com.bkanent.common.authz.AuthzRequestScope.requireBrokerIdentity();
            var listing = domain.getById(request.request().listingId());
            if (listing == null || !Integer.valueOf(0).equals(listing.getDeleted())) return false;
            if (businessWide) return true;
            return listing.getBrokerId() != null && listing.getBrokerId() == brokerId;
        }
    }

    @Override
    public boolean authorizeAssetWriteContext(
            com.bkanent.common.rag.RagContextAuthorizedListingAssetWriteRequest request,
            com.bkanent.common.rag.RagAuthEnvelope authentication) {
        if (!authorizationProperties.isEnabled()) {
            throw new com.bkanent.common.rag.RagException(
                    com.bkanent.common.rag.RagErrorCode.AUTHENTICATION_FAILED);
        }
        var principal = identity.verifyAuthorizedAssetWriteContext(request, authentication,
                authorizationProperties.decodedAuthorityPublicKeys());
        Long brokerId = principal.businessAdmin() ? null
                : request.authorizationContext().businessIdentities().get("BROKER");
        if (!principal.businessAdmin() && brokerId == null) {
            throw new com.bkanent.common.authz.AuthzException(
                    com.bkanent.common.authz.AuthzErrorCode.PERMISSION_DENIED);
        }
        var listing = domain.getById(request.request().listingId());
        if (listing == null || !Integer.valueOf(0).equals(listing.getDeleted())) return false;
        if (principal.businessAdmin()) return true;
        return brokerId != null && listing.getBrokerId() != null && listing.getBrokerId().equals(brokerId);
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
