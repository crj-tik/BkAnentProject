package com.bkanent.listing.mcp;

import com.bkanent.common.authz.AuthzErrorCode;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.listing.ListingBusinessResponse;
import com.bkanent.common.listing.ListingBusinessSearchRequest;
import com.bkanent.common.listing.ListingComparisonCreateRequest;
import com.bkanent.common.listing.ListingSourceSnapshot;
import com.bkanent.common.model.ListingDTO;
import com.bkanent.listing.comparison.ListingComparisonService;
import com.bkanent.listing.comparison.model.ListingComparisonReportResponse;
import com.bkanent.listing.config.ListingMcpConfiguration;
import com.bkanent.listing.service.ListingManagementService;
import com.bkanent.listing.source.ListingBusinessSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * 任务 4.1/4.2：MCP 工具装配与鉴权语义。
 * 开关关闭时 mcpToolCallbackProvider 不注册（ConditionalOnProperty 门控）；
 * 开启后 6 个只读工具全部可发现且方法绑定正确；工具集不含写操作；
 * 公开工具免操作码，受限工具无授权上下文时拒绝。
 */
class ListingMcpToolsTest {

    private ListingMcpTools tools(ListingManagementService listings,
                                  ListingBusinessSourceService source,
                                  ListingComparisonService comparison) {
        return new ListingMcpTools(listings, source, comparison, new ObjectMapper());
    }

    @Test
    void configurationIsGatedByAuthorizationProperty() {
        ConditionalOnProperty conditional =
                ListingMcpConfiguration.class.getDeclaredMethods()[0].getAnnotation(ConditionalOnProperty.class);
        assertNotNull(conditional);
        assertEquals("mcp.authorization.server", conditional.prefix());
        assertEquals("enabled", conditional.name()[0]);
    }

    @Test
    void sixReadOnlyToolsAreDiscoverableWithCorrectNames() {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(tools(mock(ListingManagementService.class),
                        mock(ListingBusinessSourceService.class), mock(ListingComparisonService.class)))
                .build();
        Set<String> names = new java.util.HashSet<>();
        for (ToolCallback callback : provider.getToolCallbacks()) names.add(callback.getToolDefinition().name());
        assertEquals(Set.of(
                "searchListingSummaries",
                "getListingSummary",
                "getListingDetail",
                "searchListingSource",
                "createListingComparison",
                "getComparisonReport"), names);
    }

    @Test
    void toolSetContainsNoWriteOperations() {
        // 写操作方法名精确清单（createListingComparison 是对比计算，不是房源写操作）
        Set<String> forbidden = Set.of("createListing", "updateListing", "deleteListing",
                "updateStatus", "bindAssets", "retireAsset", "registerLegacyAssets",
                "confirmOwner", "unbindOwner", "recognizeOcr", "verifyAuthenticity");
        for (Method method : ListingMcpTools.class.getDeclaredMethods()) {
            assertFalse(forbidden.contains(method.getName()),
                    "MCP 工具集不得包含写操作: " + method.getName());
        }
    }

    @Test
    void publicToolsWorkWithoutAuthorizationContext() {
        ListingManagementService listings = mock(ListingManagementService.class);
        when(listings.searchListingSummaries("朝阳")).thenReturn(List.of(mock(ListingDTO.class)));
        when(listings.getListingSummary(1L)).thenReturn(mock(ListingDTO.class));
        ListingMcpTools mcpTools = tools(listings,
                mock(ListingBusinessSourceService.class), mock(ListingComparisonService.class));

        assertFalse(mcpTools.searchListingSummaries("朝阳").isEmpty());
        assertNotNull(mcpTools.getListingSummary(1L));
    }

    @Test
    void restrictedToolsRejectWithoutAuthorizationContext() {
        ListingMcpTools mcpTools = tools(mock(ListingManagementService.class),
                mock(ListingBusinessSourceService.class), mock(ListingComparisonService.class));

        AuthzException detail = assertThrows(AuthzException.class, () -> mcpTools.getListingDetail(1L));
        assertEquals(AuthzErrorCode.AUTHENTICATION_REQUIRED, detail.code());
        assertThrows(AuthzException.class, () -> mcpTools.searchListingSource("朝阳", 10));
        assertThrows(AuthzException.class,
                () -> mcpTools.createListingComparison("[]", true));
        assertThrows(AuthzException.class, () -> mcpTools.getComparisonReport("r-1"));
    }

    @Test
    void comparisonToolDelegatesToComparisonService() throws Exception {
        ListingComparisonService comparison = mock(ListingComparisonService.class);
        ListingComparisonReportResponse report = mock(ListingComparisonReportResponse.class);
        when(comparison.create(any(ListingComparisonCreateRequest.class))).thenReturn(report);
        ListingMcpTools mcpTools = tools(mock(ListingManagementService.class),
                mock(ListingBusinessSourceService.class), comparison);

        // 受限工具经 McpAuthorizationScope 授权后委托服务层（此处以公开路径验证委托语义：
        // 直接验证服务层调用契约，授权门控由 restrictedToolsRejectWithoutAuthorizationContext 覆盖）
        String refsJson = "[{\"listingRef\":{\"sourceSystem\":\"legacy-local\",\"sourceId\":\"101\"},"
                + "\"expectedRevision\":\"r-1\"}]";
        try (var ignored = com.bkanent.common.mcp.authz.McpAuthorizationScope.open(
                verified("compare.report.create", "compare.report.create"))) {
            ListingComparisonReportResponse result = mcpTools.createListingComparison(refsJson, true);
            assertSame(report, result);
        }
        verify(comparison).create(argThat(request -> request.refs().size() == 1
                && request.refs().get(0).listingRef().sourceId().equals("101")));
    }

    @Test
    void sourceSearchDelegatesToBusinessSourceService() throws Exception {
        ListingBusinessSourceService source = mock(ListingBusinessSourceService.class);
        ListingBusinessResponse<ListingSourceSnapshot> response = new ListingBusinessResponse<>(
                ListingBusinessResponse.VERSION, List.of(), null);
        when(source.search(any(ListingBusinessSearchRequest.class))).thenReturn(response);
        ListingMcpTools mcpTools = tools(mock(ListingManagementService.class),
                source, mock(ListingComparisonService.class));

        try (var ignored = com.bkanent.common.mcp.authz.McpAuthorizationScope.open(
                verified("listing.read", "listing.read"))) {
            ListingBusinessResponse<ListingSourceSnapshot> result = mcpTools.searchListingSource("朝阳", 10);
            assertSame(response, result);
        }
        verify(source).search(argThat(request -> "朝阳".equals(request.keyword())
                && Integer.valueOf(10).equals(request.limit())));
    }

    /** 构造一个最小可用的 MCP Verified 上下文（操作码命中 + allowedOperations 包含该操作）。 */
    private static com.bkanent.common.mcp.authz.McpAuthorizationContract.Verified verified(
            String operation, String allowedOperation) {
        long now = java.time.Instant.now().getEpochSecond();
        String hash = "a".repeat(64);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var unsignedContext = new com.bkanent.common.authz.AuthorizationContext("123", 7, 2, hash,
                List.of(allowedOperation), "agent-service", "req-1", "run-1", "sess-1",
                hash, hash, nonce, now, now + 60, now + 300,
                java.util.Map.of("BROKER", 1L), "Ed25519", "auth-test", null);
        var context = com.bkanent.common.authz.AuthzSignature.sign(unsignedContext,
                signingKey());
        var freshness = com.bkanent.common.authz.AuthzSignature.sign(
                new com.bkanent.common.authz.AuthzFreshness("123", 7, 2,
                        com.bkanent.common.authz.AuthorizationStatus.ACTIVE, hash, now, now + 30,
                        nonce, "Ed25519", "auth-test", null), signingKey());
        var binding = new com.bkanent.common.authz.DelegationBinding(
                "agent-service", "listing-master-service", "123",
                List.of(allowedOperation), "run-1", null, "req-mcp-1",
                hash, hash, nonce, now, now + 60);
        return new com.bkanent.common.mcp.authz.McpAuthorizationContract.Verified(
                "123", "agent-service", "listing-master-service", operation,
                "req-mcp-1", operation, context, freshness, binding);
    }

    private static java.security.PrivateKey signingKey() {
        try {
            return java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
