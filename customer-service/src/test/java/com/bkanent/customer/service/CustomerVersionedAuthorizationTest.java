package com.bkanent.customer.service;

import com.bkanent.common.authz.AuthzException;
import com.bkanent.customer.converter.CustomerConverter;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.model.CustomerUpsertRequest;
import com.bkanent.customer.service.impl.CustomerManagementServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CustomerVersionedAuthorizationTest {
    @Test
    void brokerCanUpdateOnlyMappedCustomerAndCannotMoveItsBrokerOwnership() {
        var customers = mock(CustomerDomainService.class);
        var management = new CustomerManagementServiceImpl(customers, mock(CustomerFollowRecordDomainService.class),
                mock(OwnerEntrustRecordDomainService.class), mock(CustomerFavoriteListingDomainService.class),
                new CustomerConverter(), mock(ListingReadClient.class));
        var customer = new CustomerEntity();
        customer.setId(1L);
        customer.setBrokerId(41L);
        customer.setProfileType("BUYER");
        when(customers.getById(1L)).thenReturn(customer);
        var otherBrokerCustomer = new CustomerEntity();
        otherBrokerCustomer.setId(2L);
        otherBrokerCustomer.setBrokerId(82L);
        otherBrokerCustomer.setProfileType("BUYER");
        when(customers.getById(2L)).thenReturn(otherBrokerCustomer);

        var mismatchedBody = request(82L);
        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.update.own"), java.util.Map.of("BROKER", 41L))) {
            assertThrows(AuthzException.class, () -> management.updateProfile(1L, mismatchedBody));
            assertThrows(AuthzException.class, () -> management.updateProfile(2L, request(null)));
        }
        verify(customers, never()).updateById(any());
    }

    @Test
    void absentProofStopsBeforeReadingCustomerAndCreateUsesSignedBrokerMapping() {
        var customers = mock(CustomerDomainService.class);
        var management = new CustomerManagementServiceImpl(customers, mock(CustomerFollowRecordDomainService.class),
                mock(OwnerEntrustRecordDomainService.class), mock(CustomerFavoriteListingDomainService.class),
                new CustomerConverter(), mock(ListingReadClient.class));
        assertThrows(AuthzException.class, () -> management.updateProfile(1L, request(null)));
        verifyNoInteractions(customers);

        try (var ignored = CustomerAuthzScopeFixture.open(false,
                List.of("customer.create"), java.util.Map.of("BROKER", 41L))) {
            assertThrows(AuthzException.class, () -> management.createProfile(request(82L)));
            verify(customers, never()).save(any());
        }
    }

    private static CustomerUpsertRequest request(Long brokerId) {
        return new CustomerUpsertRequest("CUSTOMER", "客户", "13800000000", null, null, null,
                null, null, null, null, null, null, brokerId, null, null);
    }
}
