package com.bkanent.listing.service.impl;

import com.bkanent.common.authz.AuthorizationContext;
import com.bkanent.common.authz.AuthorizationStatus;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.authz.AuthzFreshness;
import com.bkanent.common.authz.AuthzSignature;
import com.bkanent.common.authz.AuthzContract;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingPriceRecordMapper;
import com.bkanent.listing.mapper.ListingStatusRecordMapper;
import com.bkanent.listing.rag.ListingRagIdentity;
import com.bkanent.listing.rag.ListingRagOutbox;
import com.bkanent.listing.service.ListingDomainService;
import com.bkanent.listing.service.ListingSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP 上下文桥接回归：McpAuthorizationScope 打开时，
 * 摘要/详情/归属校验走 mcpReader/requireMcpOwner 分支，不再依赖 AuthzRequestScope 或 RAG 凭据。
 */
class ListingMcpContextBridgeTest {

    private AutoCloseable scope;

    @AfterEach
    void closeScope() throws Exception {
        if (scope != null) scope.close();
    }

    /** 构造带指定操作的 MCP Verified 上下文并打开 scope。 */
    private void openScope(List<String> operations, Map<String, Long> identities) throws Exception {
        long now = Instant.now().getEpochSecond();
        KeyPair authority = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String hash = "a".repeat(64);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var unsignedContext = new AuthorizationContext("123", 7, 2, hash,
                operations, "agent-service", "req-1", "run-1", "sess-1",
                hash, hash, nonce, now, now + 60, now + 300,
                identities, AuthzContract.ALGORITHM, "auth-test", null);
        AuthorizationContext context = AuthzSignature.sign(unsignedContext, authority.getPrivate());
        AuthzFreshness freshness = AuthzSignature.sign(new AuthzFreshness("123", 7, 2,
                AuthorizationStatus.ACTIVE, hash, now, now + 30, nonce,
                AuthzContract.ALGORITHM, "auth-test", null), authority.getPrivate());
        var binding = new com.bkanent.common.authz.DelegationBinding(
                "agent-service", "listing-master-service", "123",
                operations, "run-1", null, "req-1", hash, hash, nonce, now, now + 60);
        var verified = new McpAuthorizationContract.Verified(
                "123", "agent-service", "listing-master-service",
                operations.isEmpty() ? null : operations.get(operations.size() - 1),
                "req-mcp-1", "tool", context, freshness, binding);
        scope = McpAuthorizationScope.open(verified);
    }

    private ListingManagementServiceImpl serviceWith(ListingEntity... listings) {
        ListingDomainService domain = mock(ListingDomainService.class);
        for (ListingEntity listing : listings) {
            Mockito.when(domain.getById(listing.getId())).thenReturn(listing);
        }
        Mockito.when(domain.search(any())).thenReturn(List.of(listings));
        return new ListingManagementServiceImpl(domain,
                new ListingConverter(),
                mock(ListingSearchService.class),
                mock(com.bkanent.listing.search.ListingKnowledgeDocumentAssembler.class),
                mock(ListingRagOutbox.class),
                mock(ListingRagIdentity.class),
                mock(ListingPriceRecordMapper.class),
                mock(ListingStatusRecordMapper.class),
                mock(com.bkanent.listing.service.ListingCommandReceiptService.class),
                mock(com.bkanent.listing.customer.CustomerProfileVerifyClient.class),
                mock(com.bkanent.listing.service.ListingAssetService.class),
                mock(com.bkanent.listing.service.ListingPrivateInfoService.class));
    }

    private static ListingEntity listing(long id, long brokerId, String status) {
        ListingEntity entity = new ListingEntity();
        entity.setId(id);
        entity.setBrokerId(brokerId);
        entity.setStatus(status);
        entity.setDeleted(0);
        entity.setTitle("测试房源 " + id);
        entity.setAddress("地址");
        entity.setLayout("2室1厅");
        entity.setArea(new java.math.BigDecimal("80"));
        entity.setTotalPrice(new java.math.BigDecimal("300"));
        return entity;
    }

    @Test
    void summariesWorkUnderMcpScopeWithListingRead() throws Exception {
        openScope(List.of("listing.read"), Map.of("BROKER", 1L));
        ListingManagementServiceImpl service = serviceWith(listing(1L, 1L, "ON_SALE"));

        List<ListingDTO> result = service.searchListingSummaries(null);
        assertFalse(result.isEmpty(), "listing.read 下 MCP 摘要应可用");
    }

    @Test
    void summaryByidWorksWithoutAuthzRequestScope() throws Exception {
        openScope(List.of("listing.read"), Map.of("BROKER", 1L));
        ListingManagementServiceImpl service = serviceWith(listing(1L, 1L, "ON_SALE"));

        ListingDTO dto = service.getListingSummary(1L);
        assertNotNull(dto, "MCP 上下文下按 ID 摘要应可用");
    }

    @Test
    void detailRejectsNonOwnerWithoutAllOperation() throws Exception {
        openScope(List.of("listing.read"), Map.of("BROKER", 2L));
        ListingManagementServiceImpl service = serviceWith(listing(1L, 1L, "ON_SALE"));

        assertThrows(AuthzException.class, () -> service.getListingDetail(1L),
                "非归属经纪人且无 listing.read.all 时详情应拒绝");
    }

    @Test
    void detailAllowsOwnerUnderMcpScope() throws Exception {
        openScope(List.of("listing.read"), Map.of("BROKER", 1L));
        ListingManagementServiceImpl service = serviceWith(listing(1L, 1L, "ON_SALE"));

        assertDoesNotThrow(() -> service.getListingDetail(1L),
                "归属经纪人 + listing.read 下 MCP 详情应可用");
    }

    @Test
    void detailAllowsBusinessAdminWithAllOperation() throws Exception {
        openScope(List.of("listing.read", "listing.read.all"), Map.of());
        ListingManagementServiceImpl service = serviceWith(listing(1L, 1L, "ON_SALE"));

        assertDoesNotThrow(() -> service.getListingDetail(1L),
                "listing.read.all 下 MCP 详情应可用");
    }
}
