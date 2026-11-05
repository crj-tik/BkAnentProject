package com.bkanent.customer.service;

import com.bkanent.customer.converter.CustomerConverter;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.entity.CustomerFollowRecordEntity;
import com.bkanent.customer.entity.CustomerListingInquiryEntity;
import com.bkanent.customer.model.CustomerFollowRecordRequest;
import com.bkanent.customer.service.impl.CustomerManagementServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 跟进记录与咨询关联测试：同客户可关联、跨客户拒绝、历史空关联兼容。
 */
class CustomerFollowRecordInquiryLinkTest {

    private static final long CUSTOMER_ID = 9L;
    private static final long BROKER_ID = 41L;

    private final CustomerDomainService customers = mock(CustomerDomainService.class);
    private final CustomerFollowRecordDomainService followRecords = mock(CustomerFollowRecordDomainService.class);
    private final CustomerListingInquiryDomainService inquiries =
            mock(CustomerListingInquiryDomainService.class);

    private CustomerManagementServiceImpl service() {
        return new CustomerManagementServiceImpl(customers, followRecords,
                mock(OwnerEntrustRecordDomainService.class), mock(CustomerFavoriteListingDomainService.class),
                new CustomerConverter(), mock(ListingReadClient.class), inquiries);
    }

    private CustomerEntity customer() {
        CustomerEntity customer = new CustomerEntity();
        customer.setId(CUSTOMER_ID);
        customer.setBrokerId(BROKER_ID);
        customer.setProfileType("CUSTOMER");
        when(customers.getById(CUSTOMER_ID)).thenReturn(customer);
        return customer;
    }

    private CustomerFollowRecordRequest request(Long inquiryId) {
        return new CustomerFollowRecordRequest(null, "PHONE", "电话回访", "INTERESTED",
                LocalDateTime.now().plusDays(3), inquiryId);
    }

    private CustomerListingInquiryEntity inquiry(long id, long customerId) {
        CustomerListingInquiryEntity inquiry = new CustomerListingInquiryEntity();
        inquiry.setId(id);
        inquiry.setCustomerId(customerId);
        inquiry.setListingId(77L);
        inquiry.setBrokerId(BROKER_ID);
        inquiry.setStatus("OPEN");
        inquiry.setValidationState("CONFIRMED");
        return inquiry;
    }

    @Test
    void followRecordCanLinkAnInquiryOfTheSameCustomer() {
        customer();
        when(inquiries.getById(5L)).thenReturn(inquiry(5L, CUSTOMER_ID));
        AtomicReference<CustomerFollowRecordEntity> saved = new AtomicReference<>();
        doAnswer(call -> {
            CustomerFollowRecordEntity entity = call.getArgument(0);
            entity.setId(50L);
            saved.set(entity);
            return true;
        }).when(followRecords).save(any());

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.follow.write.own"), Map.of("BROKER", BROKER_ID))) {
            var response = service().addFollowRecord(CUSTOMER_ID, request(5L));
            assertEquals(5L, response.inquiryId());
        }
        assertEquals(5L, saved.get().getInquiryId());
    }

    @Test
    void crossCustomerInquiryLinkIsRejectedWithoutSaving() {
        customer();
        when(inquiries.getById(5L)).thenReturn(inquiry(5L, 8L));

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.follow.write.own"), Map.of("BROKER", BROKER_ID))) {
            assertThrows(IllegalArgumentException.class,
                    () -> service().addFollowRecord(CUSTOMER_ID, request(5L)));
        }
        verify(followRecords, never()).save(any());
    }

    @Test
    void missingInquiryLinkIsRejected() {
        customer();
        when(inquiries.getById(6L)).thenReturn(null);

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.follow.write.own"), Map.of("BROKER", BROKER_ID))) {
            assertThrows(IllegalArgumentException.class,
                    () -> service().addFollowRecord(CUSTOMER_ID, request(6L)));
        }
        verify(followRecords, never()).save(any());
    }

    @Test
    void nullInquiryLinkKeepsLegacyBehaviorWithoutInquiryLookup() {
        customer();
        AtomicReference<CustomerFollowRecordEntity> saved = new AtomicReference<>();
        doAnswer(call -> {
            CustomerFollowRecordEntity entity = call.getArgument(0);
            entity.setId(51L);
            saved.set(entity);
            return true;
        }).when(followRecords).save(any());

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.follow.write.own"), Map.of("BROKER", BROKER_ID))) {
            var response = service().addFollowRecord(CUSTOMER_ID, request(null));
            assertNull(response.inquiryId());
        }
        assertNull(saved.get().getInquiryId());
        verifyNoInteractions(inquiries);
    }
}
