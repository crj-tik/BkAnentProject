package com.bkanent.listing.rag;

import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.controller.ListingController;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.customer.CustomerProfileVerifyClient;
import com.bkanent.listing.mapper.ListingPriceRecordMapper;
import com.bkanent.listing.mapper.ListingStatusRecordMapper;
import com.bkanent.listing.model.ListingDetailResponse;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import com.bkanent.listing.service.ListingAssetService;
import com.bkanent.listing.service.ListingCommandReceiptService;
import com.bkanent.listing.service.ListingMybatisH2Fixture;
import com.bkanent.listing.service.ListingPrivateInfoService;
import com.bkanent.listing.service.ListingSearchService;
import com.bkanent.listing.service.impl.ListingManagementServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ListingManagementReadTest {
    private ListingMybatisH2Fixture database;
    private ListingManagementServiceImpl service;
    private ListingController controller;

    @BeforeEach
    void setUp() {
        database = new ListingMybatisH2Fixture();
        service = new ListingManagementServiceImpl(database.newDomainService(), new ListingConverter(),
                mock(ListingSearchService.class), new ListingKnowledgeDocumentAssembler(),
                mock(ListingRagOutbox.class), new ListingRagIdentity(new ListingRagProperties(), mock(ObjectProvider.class)),
                mock(ListingPriceRecordMapper.class), mock(ListingStatusRecordMapper.class),
                mock(ListingCommandReceiptService.class), mock(CustomerProfileVerifyClient.class),
                mock(ListingAssetService.class), mock(ListingPrivateInfoService.class));
        controller = new ListingController(service);
        insert(1L, 82L, 123L, "PENDING_REVIEW", 0);
        insert(2L, 41L, null, "OFFLINE", 0);
        insert(3L, 41L, 124L, "PENDING_REVIEW", 0);
        insert(4L, 41L, 123L, "ON_SALE", 0);
        insert(5L, 41L, 123L, "OFFLINE", 1);
    }

    @AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    void managementOwnerReadsOwnPendingAndLegacyOfflineListings() {
        try (var ignored = ListingAuthzScopeFixture.open(false, List.of("listing.read"),
                List.of("listing.read"), Map.of("BROKER", 41L))) {
            assertEquals("PENDING_REVIEW", controller.detail(1L).data().status());
            assertEquals("OFFLINE", controller.detail(2L).data().status());
            assertEquals(List.of(1L, 2L, 4L), searchIds(null));
            assertEquals(List.of(2L), searchIds("OFFLINE"));
            assertEquals(RagErrorCode.FORBIDDEN,
                    assertThrows(RagException.class, () -> service.getListingDetail(1L)).code());
        }
    }

    @Test
    void ordinaryReadDoesNotIncludeOtherOwnersOrDeletedRows() {
        try (var ignored = ListingAuthzScopeFixture.open(false, List.of("listing.read"),
                List.of("listing.read"), Map.of("BROKER", 41L))) {
            assertThrows(AuthzException.class, () -> controller.detail(3L));
            assertThrows(IllegalArgumentException.class, () -> controller.detail(5L));
            assertEquals(List.of(1L), searchIds("PENDING_REVIEW"));
        }
    }

    @Test
    void businessWideReadIncludesUndeletedListingsAcrossOwners() {
        try (var ignored = ListingAuthzScopeFixture.open(true, List.of("listing.read.all"),
                List.of("listing.read.all"), Map.of())) {
            assertEquals("PENDING_REVIEW", controller.detail(3L).data().status());
            assertEquals(List.of(1L, 3L), searchIds("PENDING_REVIEW"));
            assertEquals(List.of(1L, 2L, 3L, 4L), searchIds(null));
        }
    }

    @Test
    void businessWideReaderWithoutAdminIdentityCanOnlySeeOwnUnpublishedListings() {
        try (var ignored = ListingAuthzScopeFixture.open(false, List.of("listing.read.all"),
                List.of("listing.read.all"), Map.of("BROKER", 41L))) {
            assertEquals("PENDING_REVIEW", controller.detail(1L).data().status());
            assertThrows(AuthzException.class, () -> controller.detail(3L));
            assertEquals(List.of(1L), searchIds("PENDING_REVIEW"));
            assertEquals(List.of(1L, 2L, 4L), searchIds(null));
        }
    }

    private List<Long> searchIds(String status) {
        return controller.search(null, status, null, null, null, null, null, null).data().stream()
                .map(ListingDetailResponse::id)
                .sorted()
                .toList();
    }

    private void insert(long id, long brokerId, Long ownerUserId, String status, int deleted) {
        database.jdbc.update("INSERT INTO listing_info (id, broker_id, owner_user_id, status, deleted) VALUES (?, ?, ?, ?, ?)",
                id, brokerId, ownerUserId, status, deleted);
    }
}
