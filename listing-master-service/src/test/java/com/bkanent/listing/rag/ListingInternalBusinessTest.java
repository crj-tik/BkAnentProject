package com.bkanent.listing.rag;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.bkanent.common.rag.AuthRagPrincipal;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.listing.customer.CustomerProfileVerifyClient;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.entity.ListingPriceRecordEntity;
import com.bkanent.listing.entity.ListingStatusRecordEntity;
import com.bkanent.listing.mapper.ListingPriceRecordMapper;
import com.bkanent.listing.mapper.ListingStatusRecordMapper;
import com.bkanent.listing.model.ListingStatusUpdateRequest;
import com.bkanent.listing.model.ListingUpsertRequest;
import com.bkanent.listing.service.ListingAssetService;
import com.bkanent.listing.service.ListingCommandReceiptService;
import com.bkanent.listing.service.ListingDomainService;
import com.bkanent.listing.service.ListingPrivateInfoService;
import com.bkanent.listing.service.ListingSearchService;
import com.bkanent.listing.service.impl.ListingManagementServiceImpl;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 内部房源登记与维护核心行为：登记幂等、业主验证失败关闭、状态机、价格历史与版本条件写。 */
class ListingInternalBusinessTest {

    private static final List<String> OPERATIONS = List.of("listing.create", "listing.update.own",
            "listing.status.update.own");

    @BeforeEach
    void initTableInfo() {
        // 单元测试无 MyBatis Plus 启动流程；初始化 ListingEntity 的 lambda 缓存供 LambdaUpdateWrapper 解析列名。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ListingEntity.class);
    }

    private ListingUpsertRequest upsert(String requestId, BigDecimal totalPriceWan) {
        return new ListingUpsertRequest(9L, 55L, requestId, "title", "address", "community", "district",
                "layout", BigDecimal.ONE, totalPriceWan, "floor", "decoration", "school", "traffic",
                "owner", "certificate", "certificateUrl", "contractUrl", List.of(), List.of(), List.of());
    }

    private ListingEntity listing(String status, BigDecimal askingYuan, BigDecimal totalWan) {
        var entity = new ListingEntity();
        entity.setId(11L);
        entity.setBrokerId(9L);
        entity.setOwnerCustomerId(55L);
        entity.setDeleted(0);
        entity.setStatus(status);
        entity.setTitle("title");
        entity.setAddress("address");
        entity.setLayout("layout");
        entity.setArea(BigDecimal.ONE);
        entity.setAskingPriceYuan(askingYuan);
        entity.setTotalPrice(totalWan);
        entity.setVersion(0L);
        return entity;
    }

    private ListingManagementServiceImpl service(ListingDomainService domain, ListingRagOutbox outbox,
                                                 ListingRagIdentity identity, CustomerProfileVerifyClient ownerVerify,
                                                 ListingCommandReceiptService receipts,
                                                 ListingPriceRecordMapper priceMapper,
                                                 ListingStatusRecordMapper statusMapper) {
        return new ListingManagementServiceImpl(domain, new ListingConverter(), mock(ListingSearchService.class),
                new ListingKnowledgeDocumentAssembler(), outbox, identity, priceMapper, statusMapper,
                receipts, ownerVerify, mock(ListingAssetService.class), mock(ListingPrivateInfoService.class));
    }

    private ListingRagIdentity identity() {
        var identity = mock(ListingRagIdentity.class);
        when(identity.requireReader(any())).thenReturn(new AuthRagPrincipal(7L, "ADMIN", true, true, Long.MAX_VALUE));
        return identity;
    }

    @Test
    void createRetryWithSameRequestIdAndContentReturnsOriginalListingWithoutDuplicateCreate() {
        var domain = mock(ListingDomainService.class);
        var outbox = mock(ListingRagOutbox.class);
        var ownerVerify = mock(CustomerProfileVerifyClient.class);
        var receipts = mock(ListingCommandReceiptService.class);
        var priceMapper = mock(ListingPriceRecordMapper.class);
        var statusMapper = mock(ListingStatusRecordMapper.class);
        var service = service(domain, outbox, identity(), ownerVerify, receipts, priceMapper, statusMapper);
        byte[] hash = new byte[32];
        hash[0] = 1;
        when(receipts.requestHash(any())).thenReturn(hash);
        when(receipts.find(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ListingCommandReceiptService.Receipt(hash, 11L, "COMPLETED")));
        var created = listing("PENDING_REVIEW", BigDecimal.valueOf(100000L), BigDecimal.TEN);
        when(domain.save(any())).thenAnswer(call -> {
            ((ListingEntity) call.getArgument(0)).setId(11L);
            return true;
        });
        when(domain.getById(11L)).thenReturn(created);
        var request = upsert("register-11", BigDecimal.TEN);
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            var first = service.createListing(request);
            assertEquals(11L, first.id());
            var second = service.createListing(request);
            assertEquals(11L, second.id());
        }
        verify(domain, times(1)).save(any());
        verify(receipts, times(1)).insertSameTransaction(eq(123L), eq("listing.create"), eq("register-11"),
                eq(hash), eq(11L), eq("COMPLETED"));
        verify(priceMapper, times(1)).insert(any(ListingPriceRecordEntity.class));
        verify(statusMapper, times(1)).insert(any(ListingStatusRecordEntity.class));
        verify(outbox, times(1)).publishCurrent(11L);
        verify(domain, times(1)).getById(11L);
    }

    @Test
    void createRetryWithSameRequestIdButDifferentContentIsRejected() {
        var domain = mock(ListingDomainService.class);
        var ownerVerify = mock(CustomerProfileVerifyClient.class);
        var receipts = mock(ListingCommandReceiptService.class);
        var service = service(domain, mock(ListingRagOutbox.class), identity(), ownerVerify, receipts,
                mock(ListingPriceRecordMapper.class), mock(ListingStatusRecordMapper.class));
        byte[] original = new byte[32];
        byte[] different = new byte[32];
        different[0] = 9;
        when(receipts.requestHash(any())).thenReturn(different);
        when(receipts.find(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.of(new ListingCommandReceiptService.Receipt(original, 11L, "COMPLETED")));
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            assertEquals(RagErrorCode.INVALID_ARGUMENT,
                    assertThrows(RagException.class, () -> service.createListing(upsert("register-11", BigDecimal.ONE))).code());
        }
        // 业主档案验证先于幂等检查（校验后重验当前授权），因此验证调用发生但不创建房源。
        verify(ownerVerify, times(1)).verifyOwnerProfile(55L);
        verify(domain, never()).save(any());
    }

    @Test
    void createWithoutRequestIdCreatesNormallyWithoutReceipt() {
        var domain = mock(ListingDomainService.class);
        var receipts = mock(ListingCommandReceiptService.class);
        var ownerVerify = mock(CustomerProfileVerifyClient.class);
        when(domain.save(any())).thenAnswer(call -> {
            ((ListingEntity) call.getArgument(0)).setId(11L);
            return true;
        });
        var service = service(domain, mock(ListingRagOutbox.class), identity(), ownerVerify, receipts,
                mock(ListingPriceRecordMapper.class), mock(ListingStatusRecordMapper.class));
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            assertEquals(11L, service.createListing(upsert(null, BigDecimal.TEN)).id());
        }
        verify(receipts, never()).find(anyLong(), anyString(), anyString());
        verify(receipts, never()).insertSameTransaction(anyLong(), anyString(), anyString(), any(), any(), anyString());
        verify(ownerVerify, times(1)).verifyOwnerProfile(55L);
    }

    @Test
    void createFailsClosedWhenOwnerProfileIsNotVerifiedOrDependencyUnavailable() {
        for (RagErrorCode code : List.of(RagErrorCode.NOT_FOUND, RagErrorCode.FORBIDDEN,
                RagErrorCode.INVALID_ARGUMENT, RagErrorCode.DEPENDENCY_UNAVAILABLE)) {
            var domain = mock(ListingDomainService.class);
            var outbox = mock(ListingRagOutbox.class);
            var ownerVerify = mock(CustomerProfileVerifyClient.class);
            when(ownerVerify.verifyOwnerProfile(55L)).thenThrow(new RagException(code));
            var priceMapper = mock(ListingPriceRecordMapper.class);
            var statusMapper = mock(ListingStatusRecordMapper.class);
            var service = service(domain, outbox, identity(), ownerVerify, mock(ListingCommandReceiptService.class),
                    priceMapper, statusMapper);
            try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
                assertEquals(code, assertThrows(RagException.class,
                        () -> service.createListing(upsert(null, BigDecimal.TEN))).code());
            }
            verify(domain, never()).save(any());
            verify(priceMapper, never()).insert(any(ListingPriceRecordEntity.class));
            verify(statusMapper, never()).insert(any(ListingStatusRecordEntity.class));
            verify(outbox, never()).publishCurrent(any());
        }
    }

    @Test
    void createRequiresOwnerCustomerIdFromRequest() {
        var service = service(mock(ListingDomainService.class), mock(ListingRagOutbox.class), identity(),
                mock(CustomerProfileVerifyClient.class), mock(ListingCommandReceiptService.class),
                mock(ListingPriceRecordMapper.class), mock(ListingStatusRecordMapper.class));
        var request = new ListingUpsertRequest(9L, null, null, "title", "address", null, null, "layout",
                BigDecimal.ONE, BigDecimal.TEN, "floor", "decoration", "school", "traffic", "owner",
                "certificate", "certificateUrl", "contractUrl", List.of(), List.of(), List.of());
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            assertEquals(RagErrorCode.INVALID_ARGUMENT, assertThrows(RagException.class,
                    () -> service.createListing(request)).code());
        }
    }

    @Test
    void createWritesInitialPriceAndStatusHistoryWithRegisterAction() {
        var domain = mock(ListingDomainService.class);
        var priceMapper = mock(ListingPriceRecordMapper.class);
        var statusMapper = mock(ListingStatusRecordMapper.class);
        when(domain.save(any())).thenAnswer(call -> {
            var entity = (ListingEntity) call.getArgument(0);
            entity.setId(11L);
            return true;
        });
        var service = service(domain, mock(ListingRagOutbox.class), identity(), mock(CustomerProfileVerifyClient.class),
                mock(ListingCommandReceiptService.class), priceMapper, statusMapper);
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            service.createListing(upsert(null, new BigDecimal("320.00")));
        }
        var price = ArgumentCaptor.forClass(ListingPriceRecordEntity.class);
        verify(priceMapper).insert(price.capture());
        assertNull(price.getValue().getOldPriceYuan());
        assertEquals(new BigDecimal("3200000.00"), price.getValue().getNewPriceYuan());
        assertEquals(123L, price.getValue().getOperatorUserId());
        var status = ArgumentCaptor.forClass(ListingStatusRecordEntity.class);
        verify(statusMapper).insert(status.capture());
        assertNull(status.getValue().getFromStatus());
        assertEquals("PENDING_REVIEW", status.getValue().getToStatus());
        assertEquals("REGISTER", status.getValue().getAction());
    }

    @Test
    void legalStatusTransitionWritesHistoryAndBumpsVersion() {
        var domain = mock(ListingDomainService.class);
        var outbox = mock(ListingRagOutbox.class);
        var statusMapper = mock(ListingStatusRecordMapper.class);
        var entity = listing("PENDING_REVIEW", BigDecimal.valueOf(100000L), BigDecimal.TEN);
        when(domain.getById(11L)).thenReturn(entity);
        when(domain.update(any(ListingEntity.class), any())).thenReturn(true);
        var service = service(domain, outbox, identity(), mock(CustomerProfileVerifyClient.class),
                mock(ListingCommandReceiptService.class), mock(ListingPriceRecordMapper.class), statusMapper);
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            var response = service.updateStatus(11L, new ListingStatusUpdateRequest("ON_SALE"));
            assertEquals("ON_SALE", response.status());
        }
        assertEquals(1L, entity.getVersion());
        var status = ArgumentCaptor.forClass(ListingStatusRecordEntity.class);
        verify(statusMapper).insert(status.capture());
        assertEquals("PENDING_REVIEW", status.getValue().getFromStatus());
        assertEquals("ON_SALE", status.getValue().getToStatus());
        assertEquals("LIST", status.getValue().getAction());
        verify(outbox).publishCurrent(11L);
    }

    @Test
    void illegalStatusJumpsAreRejectedWithoutChangingData() {
        for (String from : List.of("PENDING_REVIEW", "ON_SALE", "OFFLINE", "SOLD")) {
            for (String to : List.of("PENDING_REVIEW", "ON_SALE", "OFFLINE", "SOLD")) {
                boolean legal = switch (from) {
                    case "PENDING_REVIEW" -> to.equals("ON_SALE") || to.equals("OFFLINE");
                    case "ON_SALE" -> to.equals("OFFLINE") || to.equals("SOLD");
                    case "OFFLINE" -> to.equals("ON_SALE");
                    default -> false;
                };
                var domain = mock(ListingDomainService.class);
                var outbox = mock(ListingRagOutbox.class);
                var statusMapper = mock(ListingStatusRecordMapper.class);
                var entity = listing(from, BigDecimal.valueOf(100000L), BigDecimal.TEN);
                when(domain.getById(11L)).thenReturn(entity);
                when(domain.update(any(ListingEntity.class), any())).thenReturn(true);
                var service = service(domain, outbox, identity(), mock(CustomerProfileVerifyClient.class),
                        mock(ListingCommandReceiptService.class), mock(ListingPriceRecordMapper.class), statusMapper);
                try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
                    if (legal || from.equals(to)) {
                        assertEquals(to, service.updateStatus(11L, new ListingStatusUpdateRequest(to)).status());
                    } else {
                        assertThrows(IllegalArgumentException.class,
                                () -> service.updateStatus(11L, new ListingStatusUpdateRequest(to)));
                    }
                }
                if (!legal && !from.equals(to)) {
                    assertEquals(0L, entity.getVersion());
                    assertEquals(from, entity.getStatus());
                    verify(statusMapper, never()).insert(any(ListingStatusRecordEntity.class));
                    verify(outbox, never()).publishCurrent(any());
                }
            }
        }
    }

    @Test
    void sameStatusTransitionIsNoOpWithoutHistoryOrVersionBump() {
        var domain = mock(ListingDomainService.class);
        var outbox = mock(ListingRagOutbox.class);
        var statusMapper = mock(ListingStatusRecordMapper.class);
        var entity = listing("ON_SALE", BigDecimal.valueOf(100000L), BigDecimal.TEN);
        when(domain.getById(11L)).thenReturn(entity);
        var service = service(domain, outbox, identity(), mock(CustomerProfileVerifyClient.class),
                mock(ListingCommandReceiptService.class), mock(ListingPriceRecordMapper.class), statusMapper);
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            var response = service.updateStatus(11L, new ListingStatusUpdateRequest("ON_SALE"));
            assertEquals("ON_SALE", response.status());
        }
        assertEquals(0L, entity.getVersion());
        verify(statusMapper, never()).insert(any(ListingStatusRecordEntity.class));
        verify(domain, never()).update(any(ListingEntity.class), any());
        verify(outbox, never()).publishCurrent(any());
    }

    @Test
    void listingWithoutCompletePresentationDataIsRejected() {
        var domain = mock(ListingDomainService.class);
        var statusMapper = mock(ListingStatusRecordMapper.class);
        var entity = listing("PENDING_REVIEW", null, null);
        entity.setTitle(null);
        when(domain.getById(11L)).thenReturn(entity);
        var service = service(domain, mock(ListingRagOutbox.class), identity(), mock(CustomerProfileVerifyClient.class),
                mock(ListingCommandReceiptService.class), mock(ListingPriceRecordMapper.class), statusMapper);
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> service.updateStatus(11L, new ListingStatusUpdateRequest("ON_SALE")));
            assertTrue(failure.getMessage().contains("标题"));
        }
        assertEquals("PENDING_REVIEW", entity.getStatus());
        verify(statusMapper, never()).insert(any(ListingStatusRecordEntity.class));
    }

    @Test
    void listingBackfillsAskingPriceFromWanProjectionWhenListing() {
        var domain = mock(ListingDomainService.class);
        var entity = listing("PENDING_REVIEW", null, BigDecimal.TEN);
        when(domain.getById(11L)).thenReturn(entity);
        when(domain.update(any(ListingEntity.class), any())).thenReturn(true);
        var service = service(domain, mock(ListingRagOutbox.class), identity(), mock(CustomerProfileVerifyClient.class),
                mock(ListingCommandReceiptService.class), mock(ListingPriceRecordMapper.class),
                mock(ListingStatusRecordMapper.class));
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            assertEquals("ON_SALE", service.updateStatus(11L, new ListingStatusUpdateRequest("ON_SALE")).status());
        }
        assertEquals(new BigDecimal("100000"), entity.getAskingPriceYuan());
    }

    @Test
    void priceChangeWritesHistoryAndSyncsWanProjection() {
        var domain = mock(ListingDomainService.class);
        var priceMapper = mock(ListingPriceRecordMapper.class);
        var entity = listing("PENDING_REVIEW", new BigDecimal("3200000.00"), new BigDecimal("320.00"));
        when(domain.getById(11L)).thenReturn(entity);
        when(domain.update(any(ListingEntity.class), any())).thenReturn(true);
        var service = service(domain, mock(ListingRagOutbox.class), identity(), mock(CustomerProfileVerifyClient.class),
                mock(ListingCommandReceiptService.class), priceMapper, mock(ListingStatusRecordMapper.class));
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            service.updateListing(11L, upsert(null, new BigDecimal("310.00")));
        }
        assertEquals(new BigDecimal("3100000.00"), entity.getAskingPriceYuan());
        assertEquals(new BigDecimal("310.00"), entity.getTotalPrice());
        assertEquals(1L, entity.getVersion());
        var price = ArgumentCaptor.forClass(ListingPriceRecordEntity.class);
        verify(priceMapper).insert(price.capture());
        assertEquals(new BigDecimal("3200000.00"), price.getValue().getOldPriceYuan());
        assertEquals(new BigDecimal("3100000.00"), price.getValue().getNewPriceYuan());
    }

    @Test
    void priceThatCannotLosslesslyProjectToWanIsRejected() {
        var domain = mock(ListingDomainService.class);
        var priceMapper = mock(ListingPriceRecordMapper.class);
        var entity = listing("PENDING_REVIEW", new BigDecimal("3200000.00"), new BigDecimal("320.00"));
        when(domain.getById(11L)).thenReturn(entity);
        var service = service(domain, mock(ListingRagOutbox.class), identity(), mock(CustomerProfileVerifyClient.class),
                mock(ListingCommandReceiptService.class), priceMapper, mock(ListingStatusRecordMapper.class));
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.updateListing(11L, upsert(null, new BigDecimal("320.005"))));
        }
        assertEquals(new BigDecimal("3200000.00"), entity.getAskingPriceYuan());
        assertEquals(new BigDecimal("320.00"), entity.getTotalPrice());
        assertEquals(0L, entity.getVersion());
        verify(priceMapper, never()).insert(any(ListingPriceRecordEntity.class));
        verify(domain, never()).update(any(ListingEntity.class), any());
    }

    @Test
    void versionConflictKeepsListingAndHistoryUnchanged() {
        var domain = mock(ListingDomainService.class);
        var priceMapper = mock(ListingPriceRecordMapper.class);
        var outbox = mock(ListingRagOutbox.class);
        var entity = listing("PENDING_REVIEW", new BigDecimal("3200000.00"), new BigDecimal("320.00"));
        when(domain.getById(11L)).thenReturn(entity);
        when(domain.update(any(ListingEntity.class), any())).thenReturn(false);
        var service = service(domain, outbox, identity(), mock(CustomerProfileVerifyClient.class),
                mock(ListingCommandReceiptService.class), priceMapper, mock(ListingStatusRecordMapper.class));
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            assertThrows(IllegalStateException.class,
                    () -> service.updateListing(11L, upsert(null, new BigDecimal("310.00"))));
        }
        verify(priceMapper, never()).insert(any(ListingPriceRecordEntity.class));
        verify(outbox, never()).publishCurrent(any());
    }

    @Test
    void confirmOwnerVerifiesProfileThenBindsAndPublishes() {
        var domain = mock(ListingDomainService.class);
        var outbox = mock(ListingRagOutbox.class);
        var ownerVerify = mock(CustomerProfileVerifyClient.class);
        var entity = listing("PENDING_REVIEW", BigDecimal.valueOf(100000L), BigDecimal.TEN);
        entity.setOwnerCustomerId(null);
        when(domain.getById(11L)).thenReturn(entity);
        when(domain.update(any(ListingEntity.class), any())).thenReturn(true);
        var service = service(domain, outbox, identity(), ownerVerify, mock(ListingCommandReceiptService.class),
                mock(ListingPriceRecordMapper.class), mock(ListingStatusRecordMapper.class));
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            service.confirmOwner(11L, 66L);
        }
        verify(ownerVerify).verifyOwnerProfile(66L);
        assertEquals(66L, entity.getOwnerCustomerId());
        assertEquals(1L, entity.getVersion());
        verify(outbox).publishCurrent(11L);
    }

    @Test
    void confirmOwnerFailsClosedWhenProfileVerificationFails() {
        var domain = mock(ListingDomainService.class);
        var ownerVerify = mock(CustomerProfileVerifyClient.class);
        when(ownerVerify.verifyOwnerProfile(66L)).thenThrow(new RagException(RagErrorCode.NOT_FOUND));
        var entity = listing("PENDING_REVIEW", BigDecimal.valueOf(100000L), BigDecimal.TEN);
        when(domain.getById(11L)).thenReturn(entity);
        var service = service(domain, mock(ListingRagOutbox.class), identity(), ownerVerify,
                mock(ListingCommandReceiptService.class), mock(ListingPriceRecordMapper.class),
                mock(ListingStatusRecordMapper.class));
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            assertThrows(RagException.class, () -> service.confirmOwner(11L, 66L));
        }
        assertEquals(55L, entity.getOwnerCustomerId());
        assertEquals(0L, entity.getVersion());
        verify(domain, never()).update(any(ListingEntity.class), any());
    }

    @Test
    void unbindOwnerClearsAssociationWithoutTouchingHistory() {
        var domain = mock(ListingDomainService.class);
        var outbox = mock(ListingRagOutbox.class);
        var entity = listing("PENDING_REVIEW", BigDecimal.valueOf(100000L), BigDecimal.TEN);
        when(domain.getById(11L)).thenReturn(entity);
        when(domain.update(isNull(), any())).thenReturn(true);
        var priceMapper = mock(ListingPriceRecordMapper.class);
        var statusMapper = mock(ListingStatusRecordMapper.class);
        var service = service(domain, outbox, identity(), mock(CustomerProfileVerifyClient.class),
                mock(ListingCommandReceiptService.class), priceMapper, statusMapper);
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            service.unbindOwner(11L);
        }
        assertNull(entity.getOwnerCustomerId());
        assertEquals(1L, entity.getVersion());
        verify(domain).update(isNull(), any());
        verify(outbox).publishCurrent(11L);
        verify(priceMapper, never()).insert(any(ListingPriceRecordEntity.class));
        verify(statusMapper, never()).insert(any(ListingStatusRecordEntity.class));
    }

    @Test
    void unbindOwnerWithoutAssociationIsNoOp() {
        var domain = mock(ListingDomainService.class);
        var entity = listing("PENDING_REVIEW", BigDecimal.valueOf(100000L), BigDecimal.TEN);
        entity.setOwnerCustomerId(null);
        when(domain.getById(11L)).thenReturn(entity);
        var service = service(domain, mock(ListingRagOutbox.class), identity(), mock(CustomerProfileVerifyClient.class),
                mock(ListingCommandReceiptService.class), mock(ListingPriceRecordMapper.class),
                mock(ListingStatusRecordMapper.class));
        try (var ignored = ListingAuthzScopeFixture.open(false, OPERATIONS, OPERATIONS, java.util.Map.of("BROKER", 9L))) {
            assertEquals(11L, service.unbindOwner(11L).id());
        }
        assertNull(entity.getOwnerCustomerId());
        assertEquals(0L, entity.getVersion());
        verify(domain, never()).update(isNull(), any());
    }
}
