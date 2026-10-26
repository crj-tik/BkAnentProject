package com.bkanent.listing.rag;

import com.bkanent.common.rag.AuthRagPrincipal;
import com.bkanent.common.authz.AuthzException;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.entity.ListingEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ListingVersionedAuthorizationTest {
    private final ListingRagIdentity identity = new ListingRagIdentity(new ListingRagProperties(),
            mock(ObjectProvider.class));

    @Test
    void brokerRagSearchKeepsPublicOnSaleRangeAcrossBrokerOwners() {
        try (var ignored = ListingAuthzScopeFixture.open(false, java.util.List.of("listing.read"),
                java.util.List.of("listing.read"), java.util.Map.of("BROKER", 41L))) {
            AuthRagPrincipal principal = identity.requireReader(null);
            assertTrue(ListingRagIdentity.canRead(principal, row(41L, "ON_SALE")));
            assertTrue(ListingRagIdentity.canRead(principal, row(82L, "ON_SALE")));
            assertFalse(ListingRagIdentity.canRead(principal, row(41L, "PENDING_REVIEW")));
        }
    }

    @Test
    void brokerCannotReadPrivateListingDetailOwnedByAnotherMappedBroker() {
        var domain = org.mockito.Mockito.mock(com.bkanent.listing.service.ListingDomainService.class);
        var entity = row(82L, "ON_SALE");
        entity.setId(8L);
        org.mockito.Mockito.when(domain.getById(8L)).thenReturn(entity);
        var service = new com.bkanent.listing.service.impl.ListingManagementServiceImpl(domain,
                new com.bkanent.listing.converter.ListingConverter(),
                org.mockito.Mockito.mock(com.bkanent.listing.service.ListingSearchService.class),
                new com.bkanent.listing.search.ListingKnowledgeDocumentAssembler(),
                org.mockito.Mockito.mock(ListingRagOutbox.class), identity);
        try (var ignored = ListingAuthzScopeFixture.open(false, java.util.List.of("listing.read"),
                java.util.List.of("listing.read"), java.util.Map.of("BROKER", 41L))) {
            assertThrows(AuthzException.class, () -> service.getListingDetail(8L));
        }
    }

    @Test
    void businessWideRagSearchRequiresExplicitAdminReadOperationAndDoesNotInventBroker() {
        try (var ignored = ListingAuthzScopeFixture.open(true, java.util.List.of("listing.read.all"),
                java.util.List.of("listing.read.all"), java.util.Map.of())) {
            AuthRagPrincipal principal = identity.requireReader(null);
            assertTrue(ListingRagIdentity.canRead(principal, row(82L, "OFFLINE")));
            assertThrows(AuthzException.class,
                    com.bkanent.common.authz.AuthzRequestScope::requireBrokerIdentity);
        }
    }

    @Test
    void signedSnapshotPermissionCannotReplaceTheNarrowRequestOperation() {
        try (var ignored = ListingAuthzScopeFixture.open(false,
                java.util.List.of("listing.read", "listing.index.manage"),
                java.util.List.of("listing.index.manage"), java.util.Map.of("BROKER", 41L))) {
            assertThrows(AuthzException.class, () -> identity.requireReader(null));
        }
    }

    private static ListingEntity row(Long brokerId, String status) {
        var entity = new ListingEntity();
        entity.setBrokerId(brokerId);
        entity.setStatus(status);
        entity.setDeleted(0);
        return entity;
    }
}
