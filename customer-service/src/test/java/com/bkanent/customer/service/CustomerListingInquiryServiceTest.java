package com.bkanent.customer.service;

import com.bkanent.common.authz.AuthzException;
import com.bkanent.common.rag.RagErrorCode;
import com.bkanent.common.rag.RagException;
import com.bkanent.common.rpc.ListingInquiryVerifyResponse;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.entity.CustomerListingInquiryEntity;
import com.bkanent.customer.model.CustomerInquiryCreateRequest;
import com.bkanent.customer.model.CustomerInquiryResponse;
import com.bkanent.customer.service.impl.CustomerListingInquiryServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 买方房源咨询管理服务测试：创建/幂等/失败关闭/提交后复查/补偿复查/状态机/列表。
 */
class CustomerListingInquiryServiceTest {

    private static final long CUSTOMER_ID = 9L;
    private static final long LISTING_ID = 77L;
    private static final long BROKER_ID = 41L;
    private static final long ACTOR_ID = 123L;
    private static final byte[] REQUEST_HASH = new byte[32];

    private final CustomerDomainService customers = mock(CustomerDomainService.class);
    private final CustomerListingInquiryDomainService inquiries = mock(CustomerListingInquiryDomainService.class);
    private final CustomerInquiryCommandReceiptStore receipts = mock(CustomerInquiryCommandReceiptStore.class);
    private final ListingInquiryVerifyClient verifyClient = mock(ListingInquiryVerifyClient.class);

    private CustomerListingInquiryServiceImpl service() {
        return new CustomerListingInquiryServiceImpl(customers, inquiries, receipts, verifyClient);
    }

    private CustomerEntity customer() {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(CUSTOMER_ID);
        customer.setBrokerId(BROKER_ID);
        customer.setProfileType("CUSTOMER");
        when(customers.getById(CUSTOMER_ID)).thenReturn(customer);
        return customer;
    }

    private CustomerInquiryCreateRequest request() {
        return new CustomerInquiryCreateRequest(LISTING_ID, "PHONE", "客户咨询在售房源", "req-1");
    }

    private static ListingInquiryVerifyResponse accessible(String version) {
        return new ListingInquiryVerifyResponse("verify-1", ListingInquiryVerifyResponse.ACCESSIBLE,
                LISTING_ID, "ON_SALE", version);
    }

    @Test
    void accessibleListingConfirmsInquiryAndWritesReceiptInOneFlow() {
        customer();
        when(receipts.requestHash(eq(CUSTOMER_ID), any())).thenReturn(REQUEST_HASH);
        when(receipts.find(ACTOR_ID, CustomerInquiryCommandReceiptStore.CREATE_OPERATION, "req-1"))
                .thenReturn(Optional.empty());
        when(verifyClient.verify(LISTING_ID)).thenReturn(accessible("v1"), accessible("v1"));
        AtomicReference<CustomerListingInquiryEntity> saved = new AtomicReference<>();
        doAnswer(call -> {
            CustomerListingInquiryEntity entity = call.getArgument(0);
            entity.setId(1L);
            saved.set(entity);
            return true;
        }).when(inquiries).save(any());
        when(inquiries.getById(1L)).thenAnswer(call -> saved.get());

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", BROKER_ID))) {
            CustomerInquiryResponse response = service().createInquiry(CUSTOMER_ID, request());
            assertEquals(1L, response.id());
            assertEquals(CUSTOMER_ID, response.customerId());
            assertEquals(LISTING_ID, response.listingId());
            assertEquals(BROKER_ID, response.brokerId());
            assertEquals("OPEN", response.status());
            assertEquals("CONFIRMED", response.validationState());
            assertEquals("v1", response.checkedListingVersion());
            assertNotNull(response.checkedAt());
        }

        ArgumentCaptor<CustomerListingInquiryEntity> captor =
                ArgumentCaptor.forClass(CustomerListingInquiryEntity.class);
        verify(inquiries).save(captor.capture());
        assertEquals("OPEN", captor.getValue().getStatus());
        assertEquals("CONFIRMED", captor.getValue().getValidationState());
        verify(receipts).insert(ACTOR_ID, CustomerInquiryCommandReceiptStore.CREATE_OPERATION,
                "req-1", REQUEST_HASH, 1L, "COMPLETED");
        verify(inquiries, never()).updateById(any());
    }

    @Test
    void notAccessibleListingRejectsCreationWithoutPersistingAnything() {
        customer();
        when(receipts.requestHash(eq(CUSTOMER_ID), any())).thenReturn(REQUEST_HASH);
        when(receipts.find(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(verifyClient.verify(LISTING_ID)).thenReturn(
                new ListingInquiryVerifyResponse("verify-1", ListingInquiryVerifyResponse.NOT_ACCESSIBLE,
                        null, null, null));

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", BROKER_ID))) {
            assertThrows(IllegalArgumentException.class,
                    () -> service().createInquiry(CUSTOMER_ID, request()));
        }
        verify(inquiries, never()).save(any());
        verify(receipts, never()).insert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void dependencyFailureFailsClosedWithoutCreatingInquiry() {
        customer();
        when(receipts.requestHash(eq(CUSTOMER_ID), any())).thenReturn(REQUEST_HASH);
        when(receipts.find(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(verifyClient.verify(LISTING_ID)).thenThrow(new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE));

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", BROKER_ID))) {
            assertThrows(RagException.class, () -> service().createInquiry(CUSTOMER_ID, request()));
        }
        verify(inquiries, never()).save(any());
        verify(receipts, never()).insert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void sameRequestIdRetryReturnsOriginalRecordWithoutReverification() {
        customer();
        CustomerListingInquiryEntity original = new CustomerListingInquiryEntity();
        original.setId(5L);
        original.setCustomerId(CUSTOMER_ID);
        original.setListingId(LISTING_ID);
        original.setBrokerId(BROKER_ID);
        original.setStatus("OPEN");
        original.setValidationState("CONFIRMED");
        original.setCheckedListingVersion("v1");
        when(receipts.requestHash(eq(CUSTOMER_ID), any())).thenReturn(REQUEST_HASH);
        when(receipts.find(ACTOR_ID, CustomerInquiryCommandReceiptStore.CREATE_OPERATION, "req-1"))
                .thenReturn(Optional.of(new CustomerInquiryCommandReceiptStore.Receipt(
                        ACTOR_ID, CustomerInquiryCommandReceiptStore.CREATE_OPERATION, "req-1",
                        REQUEST_HASH, 5L, "COMPLETED", LocalDateTime.now())));
        when(inquiries.getById(5L)).thenReturn(original);

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", BROKER_ID))) {
            CustomerInquiryResponse response = service().createInquiry(CUSTOMER_ID, request());
            assertEquals(5L, response.id());
            assertEquals("CONFIRMED", response.validationState());
        }
        verify(verifyClient, never()).verify(any());
        verify(inquiries, never()).save(any());
        verify(receipts, never()).insert(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void sameRequestIdWithDifferentContentIsRejected() {
        customer();
        when(receipts.requestHash(eq(CUSTOMER_ID), any())).thenReturn(REQUEST_HASH);
        when(receipts.find(ACTOR_ID, CustomerInquiryCommandReceiptStore.CREATE_OPERATION, "req-1"))
                .thenReturn(Optional.of(new CustomerInquiryCommandReceiptStore.Receipt(
                        ACTOR_ID, CustomerInquiryCommandReceiptStore.CREATE_OPERATION, "req-1",
                        new byte[32], 5L, "COMPLETED", LocalDateTime.now())));

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", BROKER_ID))) {
            assertThrows(IllegalArgumentException.class,
                    () -> service().createInquiry(CUSTOMER_ID, request()));
        }
        verify(inquiries, never()).save(any());
    }

    @Test
    void postCommitVersionChangeMarksNeedsRecheck() {
        customer();
        when(receipts.requestHash(eq(CUSTOMER_ID), any())).thenReturn(REQUEST_HASH);
        when(receipts.find(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(verifyClient.verify(LISTING_ID)).thenReturn(accessible("v1"), accessible("v2"));
        AtomicReference<CustomerListingInquiryEntity> saved = new AtomicReference<>();
        doAnswer(call -> {
            CustomerListingInquiryEntity entity = call.getArgument(0);
            entity.setId(1L);
            saved.set(entity);
            return true;
        }).when(inquiries).save(any());
        when(inquiries.getById(1L)).thenAnswer(call -> saved.get());

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", BROKER_ID))) {
            CustomerInquiryResponse response = service().createInquiry(CUSTOMER_ID, request());
            assertEquals("CONFIRMED", response.validationState());
        }
        ArgumentCaptor<CustomerListingInquiryEntity> captor =
                ArgumentCaptor.forClass(CustomerListingInquiryEntity.class);
        verify(inquiries).updateById(captor.capture());
        assertEquals("NEEDS_RECHECK", captor.getValue().getValidationState());
        assertEquals("v1", captor.getValue().getCheckedListingVersion());
    }

    @Test
    void postCommitNotAccessibleMarksNeedsRecheck() {
        customer();
        when(receipts.requestHash(eq(CUSTOMER_ID), any())).thenReturn(REQUEST_HASH);
        when(receipts.find(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(verifyClient.verify(LISTING_ID)).thenReturn(accessible("v1"),
                new ListingInquiryVerifyResponse("verify-1", ListingInquiryVerifyResponse.NOT_ACCESSIBLE,
                        null, null, null));
        AtomicReference<CustomerListingInquiryEntity> saved = new AtomicReference<>();
        doAnswer(call -> {
            CustomerListingInquiryEntity entity = call.getArgument(0);
            entity.setId(1L);
            saved.set(entity);
            return true;
        }).when(inquiries).save(any());
        when(inquiries.getById(1L)).thenAnswer(call -> saved.get());

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", BROKER_ID))) {
            service().createInquiry(CUSTOMER_ID, request());
        }
        ArgumentCaptor<CustomerListingInquiryEntity> captor =
                ArgumentCaptor.forClass(CustomerListingInquiryEntity.class);
        verify(inquiries).updateById(captor.capture());
        assertEquals("NEEDS_RECHECK", captor.getValue().getValidationState());
    }

    @Test
    void postCommitDependencyFailureKeepsConfirmedState() {
        customer();
        when(receipts.requestHash(eq(CUSTOMER_ID), any())).thenReturn(REQUEST_HASH);
        when(receipts.find(anyLong(), any(), any())).thenReturn(Optional.empty());
        when(verifyClient.verify(LISTING_ID)).thenReturn(accessible("v1"))
                .thenThrow(new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE));
        AtomicReference<CustomerListingInquiryEntity> saved = new AtomicReference<>();
        doAnswer(call -> {
            CustomerListingInquiryEntity entity = call.getArgument(0);
            entity.setId(1L);
            saved.set(entity);
            return true;
        }).when(inquiries).save(any());
        when(inquiries.getById(1L)).thenAnswer(call -> saved.get());

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", BROKER_ID))) {
            service().createInquiry(CUSTOMER_ID, request());
        }
        verify(inquiries, never()).updateById(any());
    }

    @Test
    void recheckInquiriesResolvesConfirmedKeepsDriftingAndRejectsInaccessible() {
        CustomerListingInquiryEntity sameVersion = pendingInquiry(11L, 71L, "v1");
        CustomerListingInquiryEntity drifted = pendingInquiry(12L, 72L, "v1");
        CustomerListingInquiryEntity inaccessible = pendingInquiry(13L, 73L, "v1");
        when(inquiries.listByValidationState("NEEDS_RECHECK"))
                .thenReturn(List.of(sameVersion, drifted, inaccessible));
        when(verifyClient.verify(71L)).thenReturn(
                new ListingInquiryVerifyResponse("verify-71", ListingInquiryVerifyResponse.ACCESSIBLE,
                        71L, "ON_SALE", "v1"));
        when(verifyClient.verify(72L)).thenReturn(
                new ListingInquiryVerifyResponse("verify-72", ListingInquiryVerifyResponse.ACCESSIBLE,
                        72L, "ON_SALE", "v2"));
        when(verifyClient.verify(73L)).thenReturn(
                new ListingInquiryVerifyResponse("verify-73", ListingInquiryVerifyResponse.NOT_ACCESSIBLE,
                        null, null, null));

        assertEquals(3, service().recheckInquiries());

        assertEquals("CONFIRMED", sameVersion.getValidationState());
        assertEquals("v1", sameVersion.getCheckedListingVersion());
        assertEquals("NEEDS_RECHECK", drifted.getValidationState());
        assertEquals("v2", drifted.getCheckedListingVersion());
        assertEquals("REJECTED", inaccessible.getValidationState());
        verify(inquiries).updateById(sameVersion);
        verify(inquiries).updateById(drifted);
        verify(inquiries).updateById(inaccessible);
    }

    @Test
    void recheckInquiriesSkipsDependencyFailuresWithoutStoppingOthers() {
        CustomerListingInquiryEntity first = pendingInquiry(11L, 71L, "v1");
        CustomerListingInquiryEntity second = pendingInquiry(12L, 72L, "v1");
        when(inquiries.listByValidationState("NEEDS_RECHECK")).thenReturn(List.of(first, second));
        when(verifyClient.verify(71L)).thenThrow(new RagException(RagErrorCode.DEPENDENCY_UNAVAILABLE));
        when(verifyClient.verify(72L)).thenReturn(
                new ListingInquiryVerifyResponse("verify-72", ListingInquiryVerifyResponse.ACCESSIBLE,
                        72L, "ON_SALE", "v1"));

        assertEquals(1, service().recheckInquiries());
        assertEquals("NEEDS_RECHECK", first.getValidationState());
        assertEquals("CONFIRMED", second.getValidationState());
    }

    @Test
    void statusTransitionsFollowStateMachineAndRejectIllegalMoves() {
        customer();
        CustomerListingInquiryEntity inquiry = new CustomerListingInquiryEntity();
        inquiry.setId(2L);
        inquiry.setCustomerId(CUSTOMER_ID);
        inquiry.setListingId(LISTING_ID);
        inquiry.setBrokerId(BROKER_ID);
        inquiry.setStatus("OPEN");
        inquiry.setValidationState("CONFIRMED");
        when(inquiries.getById(2L)).thenReturn(inquiry);

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", BROKER_ID))) {
            assertEquals("FOLLOWING", service().updateInquiryStatus(2L, "FOLLOWING").status());
            assertEquals("CLOSED", service().updateInquiryStatus(2L, "CLOSED").status());
            assertThrows(IllegalArgumentException.class,
                    () -> service().updateInquiryStatus(2L, "FOLLOWING"));
            assertThrows(IllegalArgumentException.class,
                    () -> service().updateInquiryStatus(2L, "OPEN"));
            assertThrows(IllegalArgumentException.class,
                    () -> service().updateInquiryStatus(2L, "PAUSED"));
        }
        verify(inquiries, times(2)).updateById(any());
    }

    @Test
    void followingInquiryCanCloseButNotReopen() {
        customer();
        CustomerListingInquiryEntity inquiry = new CustomerListingInquiryEntity();
        inquiry.setId(3L);
        inquiry.setCustomerId(CUSTOMER_ID);
        inquiry.setListingId(LISTING_ID);
        inquiry.setBrokerId(BROKER_ID);
        inquiry.setStatus("FOLLOWING");
        inquiry.setValidationState("CONFIRMED");
        when(inquiries.getById(3L)).thenReturn(inquiry);

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", BROKER_ID))) {
            assertEquals("CLOSED", service().updateInquiryStatus(3L, "CLOSED").status());
            assertThrows(IllegalArgumentException.class,
                    () -> service().updateInquiryStatus(3L, "FOLLOWING"));
        }
    }

    @Test
    void listInquiriesReturnsAllValidationStatesForAuthorizedCustomer() {
        customer();
        CustomerListingInquiryEntity confirmed = pendingInquiry(11L, 71L, "v1");
        confirmed.setValidationState("CONFIRMED");
        CustomerListingInquiryEntity pending = pendingInquiry(12L, 72L, "v2");
        when(inquiries.listByCustomer(CUSTOMER_ID)).thenReturn(List.of(confirmed, pending));

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.read.own"), Map.of("BROKER", BROKER_ID))) {
            List<CustomerInquiryResponse> responses = service().listInquiries(CUSTOMER_ID);
            assertEquals(2, responses.size());
            assertEquals("CONFIRMED", responses.get(0).validationState());
            assertEquals("NEEDS_RECHECK", responses.get(1).validationState());
        }
    }

    @Test
    void missingInquiryOperationOrForeignCustomerIsDenied() {
        CustomerEntity foreign = new CustomerEntity();
        foreign.setId(8L);
        foreign.setBrokerId(82L);
        when(customers.getById(8L)).thenReturn(foreign);

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.inquiry.write.own"), Map.of("BROKER", BROKER_ID))) {
            assertThrows(AuthzException.class,
                    () -> service().createInquiry(8L, request()));
        }
        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.follow.write.own"), Map.of("BROKER", BROKER_ID))) {
            assertThrows(AuthzException.class,
                    () -> service().createInquiry(CUSTOMER_ID, request()));
        }
        verify(inquiries, never()).save(any());
    }

    private CustomerListingInquiryEntity pendingInquiry(long id, long listingId, String version) {
        CustomerListingInquiryEntity entity = new CustomerListingInquiryEntity();
        entity.setId(id);
        entity.setCustomerId(CUSTOMER_ID);
        entity.setListingId(listingId);
        entity.setBrokerId(BROKER_ID);
        entity.setStatus("OPEN");
        entity.setValidationState("NEEDS_RECHECK");
        entity.setCheckedListingVersion(version);
        entity.setCheckedAt(LocalDateTime.now());
        return entity;
    }
}
