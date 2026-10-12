package com.bkanent.listing.rag;

import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.model.*;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import com.bkanent.listing.service.ListingDomainService;
import com.bkanent.listing.service.ListingSearchService;
import com.bkanent.listing.service.impl.ListingManagementServiceImpl;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;

class ListingManagementOutboxTest {
    @Test void everyActiveMutationLocksThenPublishesAndNeverExecutesSearchOrAgentRPC() {
        var domain = mock(ListingDomainService.class); var search = mock(ListingSearchService.class); var outbox = mock(ListingRagOutbox.class);
        var service = new ListingManagementServiceImpl(domain, new ListingConverter(), search, new ListingKnowledgeDocumentAssembler(), outbox);
        var entity = new ListingEntity(); entity.setId(11L); when(domain.getById(11L)).thenReturn(entity);
        when(domain.save(any())).thenAnswer(call -> { ((ListingEntity)call.getArgument(0)).setId(11L); return true; });
        var request = new ListingUpsertRequest(9L,"title","address","layout",BigDecimal.ONE,BigDecimal.TEN,"floor","decoration","school","traffic","owner","certificate","certificateUrl","contractUrl",List.of(),List.of(),List.of());
        service.createListing(request); service.updateListing(11L,request);
        service.updateStatus(11L,new ListingStatusUpdateRequest("ON_SALE"));
        service.bindAssets(11L,new ListingAssetBindRequest(List.of("image"),List.of("floorplan"),List.of("video")));
        service.recognizeOcr(11L,new ListingOcrRecognizeRequest("certificateUrl","contractUrl"));
        service.verifyAuthenticity(11L,new ListingVerifyRequest("certificate","owner","provider"));
        service.deleteListing(11L);
        verify(outbox,times(7)).publishCurrent(11L); verify(outbox,times(6)).lock(11L); verifyNoInteractions(search);
        var order=inOrder(outbox,domain);
        order.verify(domain).save(any()); order.verify(outbox).publishCurrent(11L);
        order.verify(outbox).lock(11L); order.verify(domain).getById(11L); order.verify(domain).updateById(any()); order.verify(outbox).publishCurrent(11L);
    }
    @Test void legacyNoCredentialRPCAndToolNoContextDenyInsteadOfGuessingBroker() {
        var management=mock(com.bkanent.listing.service.ListingManagementService.class);
        var rpc=new com.bkanent.listing.rpc.ListingMasterRpcServiceImpl(management);
        assertEquals(com.bkanent.common.rag.RagErrorCode.AUTHENTICATION_FAILED,assertThrows(com.bkanent.common.rag.RagException.class,()->rpc.searchListingsByKeyword(null)).code());
        verifyNoInteractions(management);
        var facade=mock(ListingRagFacade.class); var search=new com.bkanent.listing.service.impl.ListingSearchServiceImpl(facade);
        assertThrows(com.bkanent.common.rag.RagException.class,()->search.searchByKeyword(new com.bkanent.common.model.ListingKeywordSearchRequest("keyword",null,null,null,null,null,null,5)));
        verifyNoInteractions(facade);
    }
}
