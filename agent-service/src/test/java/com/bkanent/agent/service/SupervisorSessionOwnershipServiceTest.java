package com.bkanent.agent.service;

import com.bkanent.agent.memory.MemoryStoreClient;
import com.bkanent.agent.security.AgentPrincipalException;
import com.bkanent.common.agent.SessionMemoryResponse;
import com.bkanent.common.agent.SessionMemoryUpsertRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupervisorSessionOwnershipServiceTest {

    @Test
    void claimsNewSessionForAuthenticatedUser() {
        MemoryStoreClient memoryStoreClient = mock(MemoryStoreClient.class);
        when(memoryStoreClient.getSessionMemory("session")).thenReturn(
                Optional.empty(), Optional.of(new SessionMemoryResponse("session", "42", Map.of(), null, null, null)));
        SupervisorSessionOwnershipService service = new SupervisorSessionOwnershipService(memoryStoreClient);

        service.claim("session", "42");

        verify(memoryStoreClient).upsertSessionMemory(any(SessionMemoryUpsertRequest.class));
    }

    @Test
    void refusesToClaimSessionOwnedByAnotherUser() {
        MemoryStoreClient memoryStoreClient = mock(MemoryStoreClient.class);
        when(memoryStoreClient.getSessionMemory("session")).thenReturn(
                Optional.of(new SessionMemoryResponse("session", "7", Map.of(), null, null, null)));
        SupervisorSessionOwnershipService service = new SupervisorSessionOwnershipService(memoryStoreClient);

        assertThatThrownBy(() -> service.claim("session", "42"))
                .isInstanceOf(AgentPrincipalException.class);
        verify(memoryStoreClient, never()).upsertSessionMemory(any());
    }

    @Test
    void refusesSubscriptionWhenSessionHasNoKnownOwner() {
        MemoryStoreClient memoryStoreClient = mock(MemoryStoreClient.class);
        when(memoryStoreClient.getSessionMemory("session")).thenReturn(Optional.empty());
        SupervisorSessionOwnershipService service = new SupervisorSessionOwnershipService(memoryStoreClient);

        assertThatThrownBy(() -> service.assertOwned("session", "42"))
                .isInstanceOf(AgentPrincipalException.class);
    }
}
