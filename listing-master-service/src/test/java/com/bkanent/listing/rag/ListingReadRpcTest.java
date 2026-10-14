package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.listing.config.ListingRagProperties;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.rpc.ListingMasterRpcServiceImpl;
import com.bkanent.listing.service.ListingDomainService;
import com.bkanent.listing.service.ListingManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingReadRpcTest {
    private static final String SECRET = "consumer-independent-read-test-secret-0123456789";
    private RagAuthEnvelope sign(RagListingReadRequest request, RagActorMode mode, RagCredentialContext token) {
        long now = Instant.now().getEpochSecond();
        return RagRequestSigner.sign(new RagAuthEnvelope(1,"consumer-read","listing-master-service","customer-service",RagOperation.READ,
                request.requestId(),RagCanonicalHash.requestHash(request),mode,mode == RagActorMode.USER ? "7" : "customer-service",
                now,now+60,token == null ? null : RagCanonicalHash.credentialDigest(token),null),SECRET.getBytes(StandardCharsets.UTF_8));
    }
    private ListingRagIdentity identity() {
        var p = new ListingRagProperties(); var key = new ListingRagProperties.TrustedCaller();
        key.setCallerService("customer-service"); key.setSecret(SECRET); p.setTrustedCallers(Map.of("consumer-read",key));
        return new ListingRagIdentity(p,new StaticListableBeanFactory().getBeanProvider(com.bkanent.common.rpc.RagPrincipalRpcService.class));
    }
    @Test void serviceReadAndTamperedOrMissingUserProofAreRejectedBeforeSql() {
        var domain = mock(ListingDomainService.class); var management = mock(ListingManagementService.class);
        var rpc = new ListingMasterRpcServiceImpl(management,identity(),domain,new ListingConverter());
        var request = new RagListingReadRequest(1,"read",11L,null);
        assertEquals(RagErrorCode.FORBIDDEN, assertThrows(RagException.class,
                () -> rpc.read(request,sign(request,RagActorMode.SERVICE,null),null)).code());
        var token = new RagCredentialContext("real-access");
        assertThrows(RagException.class, () -> rpc.read(new RagListingReadRequest(1,"read",12L,null),sign(request,RagActorMode.USER,token),token));
        assertThrows(RagException.class, () -> rpc.read(request,sign(request,RagActorMode.USER,token),new RagCredentialContext("other-token")));
        assertThrows(RagException.class, () -> rpc.read(request,null,null));
        assertThrows(RagException.class, () -> rpc.getListingById(11L));
        verifyNoInteractions(management,domain);
    }
    @Test void userReadUsesDedicatedContextAndActorCannotBeSpoofed() {
        var identity = mock(ListingRagIdentity.class); var management = mock(ListingManagementService.class);
        var token = new RagCredentialContext("real-access");
        when(identity.requireReader(token)).thenReturn(new AuthRagPrincipal(7L,"ADMIN",true,true,Long.MAX_VALUE));
        when(management.searchListingSummaries("region")).thenAnswer(call -> {
            assertSame(token,ListingRagCredentialContext.current()); return List.of();
        });
        var rpc = new ListingMasterRpcServiceImpl(management,identity,mock(ListingDomainService.class),new ListingConverter());
        var request = new RagListingReadRequest(1,"read",null,"region");
        assertTrue(rpc.read(request,sign(request,RagActorMode.USER,token),token).isEmpty());
        assertThrows(RagException.class,ListingRagCredentialContext::current);
        when(identity.requireReader(token)).thenReturn(new AuthRagPrincipal(8L,"ADMIN",true,true,Long.MAX_VALUE));
        assertThrows(RagException.class, () -> rpc.read(request,sign(request,RagActorMode.USER,token),token));
        verify(management,times(1)).searchListingSummaries("region");
    }
}
