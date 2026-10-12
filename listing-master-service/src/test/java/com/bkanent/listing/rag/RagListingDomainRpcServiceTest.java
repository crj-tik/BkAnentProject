package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.rpc.RagListingDomainRpcServiceImpl;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagListingDomainRpcServiceTest {
    static RagAuthEnvelope auth(RagActorMode mode) {
        long now=Instant.now().getEpochSecond();
        return new RagAuthEnvelope(1,"agent-current","listing-master-service","agent-service",RagOperation.SUBMIT,
                "request","a".repeat(64),mode,mode==RagActorMode.USER?"7":"agent-service",now,now+60,mode==RagActorMode.USER?"b".repeat(64):null,null);
    }
    @Test void adminIndexCommitsDomainEventBeforeDispatchAndReturnsOnlyActualRagStatus() {
        var identity=mock(ListingRagIdentity.class); var backfill=mock(ListingRagBackfillService.class); var dispatcher=mock(ListingRagDispatcher.class);
        var service=new RagListingDomainRpcServiceImpl(identity,mock(ListingRagFacade.class),backfill,dispatcher,new ListingRagProperties());
        var credentials=new RagCredentialContext("admin-token");
        when(identity.requireAdmin(credentials)).thenReturn(new AuthRagPrincipal(7L,"ADMIN",true,true,Long.MAX_VALUE));
        var event=new RagIndexEvent(1,"event","event","listing.knowledge",RagDocumentIds.listing(11L),3,"1",RagIndexOperation.DELETE,"c".repeat(64),null,Instant.now());
        when(backfill.index(11L,credentials)).thenReturn(event);
        var status=new RagIndexStatus(1,"event","event","listing.knowledge",event.documentId(),3,event.contentHash(),RagIndexState.ACCEPTED,3,true,null,null,null);
        when(dispatcher.deliver("event")).thenReturn(status);
        assertSame(status,service.index(new RagListingIndexRequest(1,"request",11L),auth(RagActorMode.USER),credentials));
        var order=inOrder(identity,backfill,dispatcher);
        order.verify(identity).verifyCaller(any(),eq("request"),eq(RagOperation.SUBMIT),any(),eq(credentials));
        order.verify(identity).requireAdmin(credentials); order.verify(backfill).index(11L,credentials); order.verify(dispatcher).deliver("event");
    }
    @Test void backendFailureDoesNotReturnFalselyAcceptedQueuedDomainEvent() {
        var identity=mock(ListingRagIdentity.class); var backfill=mock(ListingRagBackfillService.class); var dispatcher=mock(ListingRagDispatcher.class);
        when(identity.requireAdmin(any())).thenReturn(new AuthRagPrincipal(7L,"ADMIN",true,true,Long.MAX_VALUE));
        var event=new RagIndexEvent(1,"event","event","listing.knowledge",RagDocumentIds.listing(11L),3,"1",RagIndexOperation.DELETE,"c".repeat(64),null,Instant.now());
        when(backfill.index(anyLong(),any())).thenReturn(event); when(dispatcher.deliver(any())).thenThrow(new RagException(RagErrorCode.TIMEOUT));
        var service=new RagListingDomainRpcServiceImpl(identity,mock(ListingRagFacade.class),backfill,dispatcher,new ListingRagProperties());
        assertEquals(RagErrorCode.TIMEOUT,assertThrows(RagException.class,()->service.index(new RagListingIndexRequest(1,"request",11L),auth(RagActorMode.USER),new RagCredentialContext("token"))).code());
    }
    @Test void serviceWriterCannotManageAndLegacyGateDefaultsClosedEvenWithSignedCaller() {
        var identity=mock(ListingRagIdentity.class);var backfill=mock(ListingRagBackfillService.class);var dispatcher=mock(ListingRagDispatcher.class);
        var service=new RagListingDomainRpcServiceImpl(identity,mock(ListingRagFacade.class),backfill,dispatcher,new ListingRagProperties());
        assertEquals(RagErrorCode.FORBIDDEN,assertThrows(RagException.class,()->service.index(new RagListingIndexRequest(1,"request",11L),auth(RagActorMode.SERVICE),null)).code());
        assertEquals(RagErrorCode.LEGACY_RECONCILIATION_REQUIRED,assertThrows(RagException.class,()->service.reconcile(new RagListingReconcileRequest(1,"request",11L,3,"c".repeat(64),true),auth(RagActorMode.SERVICE))).code());
        verifyNoInteractions(backfill,dispatcher);
    }
    @Test void mismatchedAuthenticatedActorCannotUseAdminRoleFromDifferentToken() {
        var identity=mock(ListingRagIdentity.class);var backfill=mock(ListingRagBackfillService.class);var dispatcher=mock(ListingRagDispatcher.class);
        when(identity.requireAdmin(any())).thenReturn(new AuthRagPrincipal(8L,"ADMIN",true,true,Long.MAX_VALUE));
        var service=new RagListingDomainRpcServiceImpl(identity,mock(ListingRagFacade.class),backfill,dispatcher,new ListingRagProperties());
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED,assertThrows(RagException.class,()->service.index(new RagListingIndexRequest(1,"request",11L),auth(RagActorMode.USER),new RagCredentialContext("different"))).code());
        verifyNoInteractions(backfill,dispatcher);
    }
}
