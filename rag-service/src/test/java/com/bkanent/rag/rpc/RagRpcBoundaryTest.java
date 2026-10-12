package com.bkanent.rag.rpc;

import com.bkanent.common.rag.*;
import com.bkanent.rag.config.ManagedRagSpaceRegistry;
import com.bkanent.rag.index.RagIndexService;
import com.bkanent.rag.retrieval.RagRetrievalService;
import com.bkanent.rag.security.RagAccessControl;
import com.bkanent.rag.storage.RagSpaceDefinition;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagRpcBoundaryTest {
    @Test void searchRejectsBeforeRetrievalAndPassesNonOverridableMandatoryFilters() {
        var access = mock(RagAccessControl.class);
        var retrieval = mock(RagRetrievalService.class);
        var rpc = new RagRetrievalRpcServiceImpl(access, retrieval);
        var request = RagSearchRequest.defaults("req", "memory.preference", "query");
        when(access.search(request, null, null, null)).thenThrow(new RagException(RagErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> rpc.search(request, null, null, null)).isInstanceOf(RagException.class);
        verifyNoInteractions(retrieval);
        List<RagFilter> mandatory = List.of(new RagFilter("userId", RagFilterOperator.EQ, RagValue.string("7")));
        doReturn(mandatory).when(access).search(request, null, null, null);
        rpc.search(request, null, null, null);
        verify(retrieval).search(request, mandatory);
    }
    @Test void indexStatusCannotCallLedgerWhenUnauthorized() {
        var access = mock(RagAccessControl.class);
        var ledger = mock(RagIndexService.class);
        var rpc = new RagIndexRpcServiceImpl(access, ledger);
        var request = new RagStatusRequest(1, "req", "memory.preference", "other-space-event", null);
        doThrow(new RagException(RagErrorCode.FORBIDDEN)).when(access).status(request, null);
        assertThatThrownBy(() -> rpc.getStatus(request, null)).isInstanceOf(RagException.class);
        verifyNoInteractions(ledger);
    }
    @Test void submitAuthorizationPrecedesSchemaAndPersistence() {
        var access = mock(RagAccessControl.class);
        var ledger = mock(RagIndexService.class);
        var rpc = new RagIndexRpcServiceImpl(access, ledger);
        var event = new RagIndexEvent(1, "req", "event", "memory.preference", "document", 1, "schema-v1", RagIndexOperation.DELETE,
                RagCanonicalHash.documentHash(RagIndexOperation.DELETE, null), null, Instant.now());
        when(access.submit(event, null)).thenThrow(new RagException(RagErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> rpc.submit(event, null)).isInstanceOf(RagException.class);
        verifyNoInteractions(ledger);
    }
    @Test void eachAdminMethodAuthorizesExactSpaceOperationBeforeAnyBackendOrReplay() {
        var access = mock(RagAccessControl.class);
        var registry = mock(ManagedRagSpaceRegistry.class);
        var ledger = mock(RagIndexService.class);
        var rpc = new RagSpaceAdminRpcServiceImpl(access, registry, ledger);
        var inspect = new RagInspectRequest(1, "inspect", "memory.preference");
        var provision = new RagProvisionRequest(1, "provision", "memory.preference", "schema-v1", 3);
        var replay = new RagReplayRequest(1, "replay", "memory.preference", "foreign-event", null);
        for (var operation : List.of(RagOperation.INSPECT, RagOperation.PROVISION, RagOperation.REPLAY)) {
            Object request = operation == RagOperation.INSPECT ? inspect : operation == RagOperation.PROVISION ? provision : replay;
            String requestId = operation.name().toLowerCase();
            doThrow(new RagException(RagErrorCode.FORBIDDEN)).when(access).admin(request, "memory.preference", requestId, operation, null, null);
        }
        assertThatThrownBy(() -> rpc.inspect(inspect, null, null)).isInstanceOf(RagException.class);
        assertThatThrownBy(() -> rpc.provision(provision, null, null)).isInstanceOf(RagException.class);
        assertThatThrownBy(() -> rpc.replay(replay, null, null)).isInstanceOf(RagException.class);
        verifyNoInteractions(registry, ledger);
    }
    @Test void nullRequestsAreInvalidWithoutAnyInvocation() {
        var access = mock(RagAccessControl.class);
        var ledger = mock(RagIndexService.class);
        var rpc = new RagIndexRpcServiceImpl(access, ledger);
        assertThatThrownBy(() -> rpc.submit(null, null)).isInstanceOf(RagException.class);
        assertThatThrownBy(() -> rpc.getStatus(null, null)).isInstanceOf(RagException.class);
        verifyNoInteractions(access, ledger);
    }
}
