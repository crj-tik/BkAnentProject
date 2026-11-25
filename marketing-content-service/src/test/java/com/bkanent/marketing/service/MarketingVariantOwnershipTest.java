package com.bkanent.marketing.service;

import com.bkanent.common.authz.*;
import com.bkanent.common.mcp.authz.McpAuthorizationContract;
import com.bkanent.common.mcp.authz.McpAuthorizationScope;
import com.bkanent.marketing.campaign.CampaignDomainException;
import com.bkanent.marketing.converter.MarketingContentConverter;
import com.bkanent.marketing.entity.MarketingContentEntity;
import com.bkanent.marketing.model.MarketingContentUpsertRequest;
import com.bkanent.marketing.service.impl.MarketingAssetServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketingVariantOwnershipTest {
    private static final String OWN = "marketing.content.variant.create.own";
    private static final String ALL = "marketing.content.variant.create.all";

    @Test
    void wideCallerUsesItsOwnBrokerInsteadOfSourceBroker() throws Exception {
        Fixture fixture = new Fixture(7L, 51L);
        try (var ignored = McpAuthorizationScope.open(context(ALL, Map.of("BROKER", 99L)))) {
            fixture.service.createPlatformVariant(8L, request());
        }
        assertEquals(42L, fixture.saved.getOwnerUserId());
        assertEquals(99L, fixture.saved.getOwnerBrokerId());
        assertEquals(7L, fixture.source.getOwnerUserId());
        assertEquals(51L, fixture.source.getOwnerBrokerId());
        verify(fixture.search).indexContent(fixture.saved);
    }

    @Test
    void wideCallerWithoutBrokerDoesNotInheritSourceIdentity() throws Exception {
        Fixture fixture = new Fixture(7L, 51L);
        try (var ignored = McpAuthorizationScope.open(context(ALL, Map.of()))) {
            fixture.service.createPlatformVariant(8L, request());
        }
        assertEquals(42L, fixture.saved.getOwnerUserId());
        assertNull(fixture.saved.getOwnerBrokerId());
    }

    @Test
    void ownCallerKeepsUserAndBrokerFromSameContext() throws Exception {
        Fixture fixture = new Fixture(42L, 99L);
        try (var ignored = McpAuthorizationScope.open(context(OWN, Map.of("BROKER", 99L)))) {
            fixture.service.createPlatformVariant(8L, request());
        }
        assertEquals(42L, fixture.saved.getOwnerUserId());
        assertEquals(99L, fixture.saved.getOwnerBrokerId());
    }

    @Test
    void ownCallerCannotCopyAnotherUsersContentEvenWithSameBrokerNumber() throws Exception {
        Fixture fixture = new Fixture(7L, 99L);
        try (var ignored = McpAuthorizationScope.open(context(OWN, Map.of("BROKER", 99L)))) {
            assertThrows(AuthzException.class, () -> fixture.service.createPlatformVariant(8L, request()));
        }
        assertNull(fixture.saved);
        verifyNoInteractions(fixture.search);
    }

    @Test
    void ownCallerWithoutBrokerIsRejectedBeforeSaving() throws Exception {
        Fixture fixture = new Fixture(42L, 99L);
        try (var ignored = McpAuthorizationScope.open(context(OWN, Map.of()))) {
            assertThrows(AuthzException.class, () -> fixture.service.createPlatformVariant(8L, request()));
        }
        assertNull(fixture.saved);
        verifyNoInteractions(fixture.search);
    }

    @Test
    void manualDraftAndVariantRejectUnavailableListingBeforeSaving() throws Exception {
        Fixture fixture = new Fixture(42L, 99L);
        doThrow(CampaignDomainException.invalid("房源不可选用")).when(fixture.listings).requireOnSale(3L);
        try (var ignored = McpAuthorizationScope.open(context("marketing.content.create.own", Map.of("BROKER", 99L)))) {
            assertThrows(CampaignDomainException.class, () -> fixture.service.createContent(request()));
        }
        try (var ignored = McpAuthorizationScope.open(context(OWN, Map.of("BROKER", 99L)))) {
            assertThrows(CampaignDomainException.class, () -> fixture.service.createPlatformVariant(8L, request()));
        }
        verify(fixture.listings, times(2)).requireOnSale(3L);
        assertNull(fixture.saved);
        verifyNoInteractions(fixture.search);
    }

    private static MarketingContentUpsertRequest request() {
        return new MarketingContentUpsertRequest(3L, "douyin", "标题", "TEXT", "文案",
                List.of(), null, null, "douyin", List.of(), "DRAFT", null, "manual");
    }

    private static McpAuthorizationContract.Verified context(String operation, Map<String, Long> identities) {
        long now = Instant.now().getEpochSecond();
        String hash = "0".repeat(64);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var context = new AuthorizationContext("42", 1, 1, hash, List.of(operation), "marketing-content-service",
                "req-1", "run-1", "session-1", hash, hash, nonce, now - 1, now + 60, now + 60,
                identities, "Ed25519", "auth-current", null);
        var freshness = new AuthzFreshness("42", 1, 1, AuthorizationStatus.ACTIVE, hash,
                now - 1, now + 20, nonce, "Ed25519", "auth-current", null);
        var binding = new DelegationBinding("agent-service", "marketing-content-service", "42", List.of(operation),
                "run-1", null, "req-1", hash, hash, nonce, now - 1, now + 20);
        return new McpAuthorizationContract.Verified("42", "agent-service", "marketing-content-service", operation,
                "req-1", "variant", context, freshness, binding);
    }

    private static class Fixture {
        private final MarketingContentSearchService search = mock(MarketingContentSearchService.class);
        private final MarketingListingReadClient listings = mock(MarketingListingReadClient.class);
        private final MarketingContentEntity source = new MarketingContentEntity();
        private MarketingContentEntity saved;
        private final MarketingAssetServiceImpl service = new MarketingAssetServiceImpl(new MarketingContentConverter(), search, listings) {
            @Override public MarketingContentEntity getById(java.io.Serializable id) { return source; }
            @Override public boolean save(MarketingContentEntity entity) {
                entity.setId(9L);
                saved = entity;
                return true;
            }
        };

        private Fixture(Long owner, Long broker) {
            source.setId(8L);
            source.setOwnerUserId(owner);
            source.setOwnerBrokerId(broker);
            source.setVersionNo(1);
        }
    }
}
