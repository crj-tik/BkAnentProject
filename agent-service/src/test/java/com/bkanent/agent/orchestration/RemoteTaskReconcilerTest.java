package com.bkanent.agent.orchestration;

import com.bkanent.agent.client.A2aAgentClient;
import com.bkanent.agent.client.AcceptedA2aTask;
import com.bkanent.agent.model.distributed.SupervisorTaskRequest;
import com.bkanent.agent.registry.*;
import com.bkanent.agent.service.A2aExecutionService;
import com.bkanent.agent.service.AgentPermissionService;
import com.bkanent.common.agent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteTaskReconcilerTest {
    @Test
    void persistedAssociationRestoresOriginalEndpointAndOnlyQueriesOrCancels() throws Exception {
        var store = OrchestrationStoreTest.database(new ObjectMapper());
        store.register("run", new SupervisorTaskRequest("session", "1", "run", "trace", "find", Map.of(), "api", false));
        store.prepare("run", "call", "a2a:listing-agent", "{}");
        store.claim("run", "call");
        var descriptor = new RegisteredAgentDescriptor("listing-agent", "http://old", "/card", "/a2a",
                AgentRuntimeType.ALIBABA_A2A, AgentDescriptorSource.STATIC_CONFIG, null, Map.of());
        var request = new AgentTaskInvokeRequest("session", "child", "run", "trace", "supervisor-agent", "listing-agent",
                null, null, "find", Map.of("userId", "1"), List.of(), List.of(), "json", "call", false);
        store.unknown("run", "call");
        // An accepted task can arrive after the caller's deadline. Keep that original association.
        store.remoteAccepted("run", "call", new AcceptedA2aTask(descriptor, "remote", request));
        var client = mock(A2aAgentClient.class);
        var execution = mock(A2aExecutionService.class);
        var permission = mock(AgentPermissionService.class);
        when(execution.queryChildAsyncTaskStatus(descriptor, "remote"))
                .thenReturn(new A2aAsyncTaskStatusResponse("session", "child", "listing-agent", "remote", "COMPLETED", null, null, null, "trace"));
        var restarted = new RemoteTaskReconciler(store, client, permission, execution);
        assertThat(restarted.reconcile("run", "call", "1", true).status()).isEqualTo("COMPLETED");
        verify(client).restoreAsyncTask(descriptor, "remote", request);
        verify(client).cancelAsyncTask(descriptor, "remote");
        verifyNoMoreInteractions(client);
        restarted.reconcile("run", "call", "1", false);
        verify(execution, times(1)).queryChildAsyncTaskStatus(descriptor, "remote");
        assertThatThrownBy(() -> restarted.reconcile("run", "call", "2", false)).hasMessage("CONTINUATION_OWNER_MISMATCH");
    }

    @Test
    void missingRemoteAssociationDoesNotSubmitOrGuessAnEndpoint() throws Exception {
        var store = OrchestrationStoreTest.database(new ObjectMapper());
        store.register("run", new SupervisorTaskRequest("session", "1", "run", "trace", "find", Map.of(), "api", false));
        store.prepare("run", "call", "a2a:listing-agent", "{}");
        store.unknown("run", "call");
        var client = mock(A2aAgentClient.class);
        var execution = mock(A2aExecutionService.class);
        var reconciler = new RemoteTaskReconciler(store, client, mock(AgentPermissionService.class), execution);
        assertThat(reconciler.reconcile("run", "call", "1", false).status()).isEqualTo("OUTCOME_UNKNOWN");
        verifyNoInteractions(client, execution);
    }
}
