package com.bkanent.customer.service.impl;

import com.bkanent.customer.config.CustomerReminderProperties;
import com.bkanent.customer.service.CustomerDomainService;
import com.bkanent.customer.service.CustomerNotificationPermitClient;
import com.bkanent.customer.service.OwnerEntrustRecordDomainService;
import com.bkanent.customer.entity.CustomerEntity;
import com.bkanent.customer.entity.OwnerEntrustRecordEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CustomerReminderServiceImplAuthorizationTest {
    @Test
    void unresolvedBrokerToAuthUserMappingKeepsReminderDisabled() {
        OwnerEntrustRecordDomainService entrusts = mock(OwnerEntrustRecordDomainService.class);
        CustomerDomainService customers = mock(CustomerDomainService.class);
        CustomerReminderProperties properties = new CustomerReminderProperties();
        CustomerReminderServiceImpl service = new CustomerReminderServiceImpl(entrusts, customers, properties,
                mock(CustomerNotificationPermitClient.class));

        assertThat(properties.isEnabled()).isFalse();
        assertThat(service.sendEntrustExpiryReminders(7)).isZero();
        verifyNoInteractions(entrusts, customers);
    }

    @Test
    void explicitEnableSendsOnlyToTheTrustedUserOwnerWithIdempotentKey() {
        OwnerEntrustRecordDomainService entrusts = mock(OwnerEntrustRecordDomainService.class);
        CustomerDomainService customers = mock(CustomerDomainService.class);
        CustomerNotificationPermitClient notifications = mock(CustomerNotificationPermitClient.class);
        CustomerReminderProperties properties = new CustomerReminderProperties();
        properties.setEnabled(true);
        CustomerReminderServiceImpl service = new CustomerReminderServiceImpl(entrusts, customers, properties, notifications);
        OwnerEntrustRecordEntity entrust = new OwnerEntrustRecordEntity();
        entrust.setId(77L);
        entrust.setCustomerId(88L);
        entrust.setEntrustEndDate(LocalDate.of(2026, 12, 1));
        CustomerEntity owner = new CustomerEntity();
        owner.setOwnerUserId(42L);
        org.mockito.Mockito.when(entrusts.listExpiringWithinDays(7)).thenReturn(List.of(entrust));
        org.mockito.Mockito.when(customers.getById(88L)).thenReturn(owner);

        assertThat(service.sendEntrustExpiryReminders(7)).isEqualTo(1);
        org.mockito.Mockito.verify(notifications).sendEntrustReminder(42L, "房屋委托即将到期",
                "您负责的业主委托记录即将到期，到期日期：2026-12-01。请核实并及时跟进。",
                "customer-entrust-expiring:77:2026-12-01");
    }

    @Test
    void ownerlessLegacyRowsAreSkippedWithoutUsingBrokerIdAsRecipient() {
        OwnerEntrustRecordDomainService entrusts = mock(OwnerEntrustRecordDomainService.class);
        CustomerDomainService customers = mock(CustomerDomainService.class);
        CustomerNotificationPermitClient notifications = mock(CustomerNotificationPermitClient.class);
        CustomerReminderProperties properties = new CustomerReminderProperties();
        properties.setEnabled(true);
        CustomerReminderServiceImpl service = new CustomerReminderServiceImpl(entrusts, customers, properties, notifications);
        OwnerEntrustRecordEntity entrust = new OwnerEntrustRecordEntity();
        entrust.setId(77L);
        entrust.setCustomerId(88L);
        org.mockito.Mockito.when(entrusts.listExpiringWithinDays(7)).thenReturn(List.of(entrust));
        org.mockito.Mockito.when(customers.getById(88L)).thenReturn(new CustomerEntity());

        assertThat(service.sendEntrustExpiryReminders(7)).isZero();
        verifyNoInteractions(notifications);
    }
}
