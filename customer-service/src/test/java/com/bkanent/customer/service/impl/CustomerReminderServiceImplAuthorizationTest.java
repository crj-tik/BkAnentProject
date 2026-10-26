package com.bkanent.customer.service.impl;

import com.bkanent.customer.config.CustomerReminderProperties;
import com.bkanent.customer.service.CustomerDomainService;
import com.bkanent.customer.service.OwnerEntrustRecordDomainService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CustomerReminderServiceImplAuthorizationTest {
    @Test
    void unresolvedBrokerToAuthUserMappingKeepsReminderDisabled() {
        OwnerEntrustRecordDomainService entrusts = mock(OwnerEntrustRecordDomainService.class);
        CustomerDomainService customers = mock(CustomerDomainService.class);
        CustomerReminderProperties properties = new CustomerReminderProperties();
        CustomerReminderServiceImpl service = new CustomerReminderServiceImpl(entrusts, customers, properties);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(service.sendEntrustExpiryReminders(7)).isZero();
        verifyNoInteractions(entrusts, customers);
    }

    @Test
    void explicitEnableStillSkipsUntilRecipientBindingExists() {
        OwnerEntrustRecordDomainService entrusts = mock(OwnerEntrustRecordDomainService.class);
        CustomerDomainService customers = mock(CustomerDomainService.class);
        CustomerReminderProperties properties = new CustomerReminderProperties();
        properties.setEnabled(true);
        CustomerReminderServiceImpl service = new CustomerReminderServiceImpl(entrusts, customers, properties);

        assertThat(service.sendEntrustExpiryReminders(7)).isZero();
        verifyNoInteractions(entrusts, customers);
    }
}
