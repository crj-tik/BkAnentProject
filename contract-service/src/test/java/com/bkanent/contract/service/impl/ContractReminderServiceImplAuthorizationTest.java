package com.bkanent.contract.service.impl;

import com.bkanent.contract.config.ContractReminderProperties;
import com.bkanent.contract.service.ContractDomainService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ContractReminderServiceImplAuthorizationTest {
    @Test
    void unresolvedBrokerToAuthUserMappingKeepsReminderDisabled() {
        ContractDomainService contracts = mock(ContractDomainService.class);
        ContractReminderProperties properties = new ContractReminderProperties();
        ContractReminderServiceImpl service = new ContractReminderServiceImpl(contracts, properties);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(service.sendPendingSignReminders(3)).isZero();
        verifyNoInteractions(contracts);
    }

    @Test
    void explicitEnableStillSkipsUntilRecipientBindingExists() {
        ContractDomainService contracts = mock(ContractDomainService.class);
        ContractReminderProperties properties = new ContractReminderProperties();
        properties.setEnabled(true);
        ContractReminderServiceImpl service = new ContractReminderServiceImpl(contracts, properties);

        assertThat(service.sendPendingSignReminders(3)).isZero();
        verifyNoInteractions(contracts);
    }
}
