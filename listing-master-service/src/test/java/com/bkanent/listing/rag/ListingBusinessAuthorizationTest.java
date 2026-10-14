package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.model.ListingQueryRequest;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import com.bkanent.listing.service.ListingDomainService;
import com.bkanent.listing.service.ListingSearchService;
import com.bkanent.listing.service.impl.ListingManagementServiceImpl;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingBusinessAuthorizationTest {
    private AuthRagPrincipal principal(String role) { return new AuthRagPrincipal(7L, role, true, true, Long.MAX_VALUE); }
    private ListingEntity row(String status, int deleted) {
        var row = new ListingEntity(); row.setId(11L); row.setStatus(status); row.setDeleted(deleted); return row;
    }
    @Test void adminReadsEveryNonDeletedStatusMemberOnlyOnSaleAndNoThirdRole() {
        for (String status : List.of("PENDING_REVIEW", "ON_SALE", "SOLD", "OFFLINE")) {
            assertTrue(ListingRagIdentity.canRead(principal("ADMIN"), row(status, 0)));
            assertEquals("ON_SALE".equals(status), ListingRagIdentity.canRead(principal("BROKER"), row(status, 0)));
            assertFalse(ListingRagIdentity.canRead(principal("ADMIN"), row(status, 1)));
        }
        assertThrows(RagException.class, () -> ListingRagIdentity.activeOnly(principal("MANAGER")));
        assertThrows(RagException.class, () -> ListingRagIdentity.activeOnly(new AuthRagPrincipal(7L,"ADMIN",false,true,Long.MAX_VALUE)));
        assertThrows(RagException.class, () -> ListingRagIdentity.activeOnly(new AuthRagPrincipal(7L,"ADMIN",true,false,Long.MAX_VALUE)));
        assertThrows(RagException.class, () -> ListingRagIdentity.activeOnly(new AuthRagPrincipal(7L,"ADMIN",true,true,1)));
    }
    @Test void detailSummaryToolsRequireRealCredentialContextAndFinalStatusPolicy() {
        var domain = mock(ListingDomainService.class); var identity = mock(ListingRagIdentity.class);
        var service = new ListingManagementServiceImpl(domain, new ListingConverter(), mock(ListingSearchService.class),
                new ListingKnowledgeDocumentAssembler(), mock(ListingRagOutbox.class), identity);
        var tools = new com.bkanent.listing.tool.ListingTools(service);
        assertThrows(RagException.class, () -> tools.getListingSummary(11L));
        verifyNoInteractions(domain, identity);
        when(domain.getById(11L)).thenReturn(row("OFFLINE", 0));
        when(identity.requireReader(any())).thenReturn(principal("BROKER"));
        try (var ignored = ListingRagCredentialContext.open(new RagCredentialContext("dedicated-access"))) {
            assertThrows(RagException.class, () -> tools.getListingDetail(11L));
            assertThrows(RagException.class, () -> tools.getListingSummary(11L));
            when(identity.requireReader(any())).thenReturn(principal("ADMIN"));
            assertEquals("OFFLINE", tools.getListingSummary(11L).status());
        }
        assertThrows(RagException.class, ListingRagCredentialContext::current);
    }
    private ListingManagementServiceImpl summaryService(ListingDomainService domain, ListingRagIdentity identity) {
        return new ListingManagementServiceImpl(domain, new ListingConverter(), mock(ListingSearchService.class),
                new ListingKnowledgeDocumentAssembler(), mock(ListingRagOutbox.class), identity);
    }
    @Test void singleSummaryRejectsTokenRevokedDuringSql() {
        var domain = mock(ListingDomainService.class); var identity = mock(ListingRagIdentity.class);
        var credentials = new RagCredentialContext("dedicated-access");
        when(identity.requireReader(credentials)).thenReturn(principal("ADMIN"));
        when(domain.getById(11L)).thenAnswer(invocation -> {
            when(identity.requireReader(credentials)).thenThrow(new RagException(RagErrorCode.AUTHENTICATION_FAILED));
            return row("OFFLINE", 0);
        });
        var service = summaryService(domain, identity);
        try (var ignored = ListingRagCredentialContext.open(credentials)) {
            assertEquals(RagErrorCode.AUTHENTICATION_FAILED,
                    assertThrows(RagException.class, () -> service.getListingSummary(11L)).code());
        }
        verify(domain).getById(11L);
        verify(identity, times(2)).requireReader(credentials);
    }
    @Test void singleSummaryRejectsOfflineAfterAdminDowngradeDuringSql() {
        var domain = mock(ListingDomainService.class); var identity = mock(ListingRagIdentity.class);
        var credentials = new RagCredentialContext("dedicated-access");
        when(identity.requireReader(credentials)).thenReturn(principal("ADMIN"));
        when(domain.getById(11L)).thenAnswer(invocation -> {
            when(identity.requireReader(credentials)).thenReturn(principal("BROKER"));
            return row("OFFLINE", 0);
        });
        var service = summaryService(domain, identity);
        try (var ignored = ListingRagCredentialContext.open(credentials)) {
            assertEquals(RagErrorCode.FORBIDDEN,
                    assertThrows(RagException.class, () -> service.getListingSummary(11L)).code());
        }
        verify(identity, times(2)).requireReader(credentials);
    }
    @Test void singleSummaryAllowsOnSaleAfterAdminDowngradeDuringSql() {
        var domain = mock(ListingDomainService.class); var identity = mock(ListingRagIdentity.class);
        var credentials = new RagCredentialContext("dedicated-access");
        when(identity.requireReader(credentials)).thenReturn(principal("ADMIN"));
        when(domain.getById(11L)).thenAnswer(invocation -> {
            when(identity.requireReader(credentials)).thenReturn(principal("BROKER"));
            return row("ON_SALE", 0);
        });
        var service = summaryService(domain, identity);
        try (var ignored = ListingRagCredentialContext.open(credentials)) {
            assertEquals("ON_SALE", service.getListingSummary(11L).status());
        }
        verify(identity, times(2)).requireReader(credentials);
    }
    @Test void singleSummaryRejectsActorDriftDuringSqlEvenForReadableRow() {
        var domain = mock(ListingDomainService.class); var identity = mock(ListingRagIdentity.class);
        var credentials = new RagCredentialContext("dedicated-access");
        when(identity.requireReader(credentials)).thenReturn(principal("ADMIN"));
        when(domain.getById(11L)).thenAnswer(invocation -> {
            when(identity.requireReader(credentials)).thenReturn(new AuthRagPrincipal(8L, "ADMIN", true, true, Long.MAX_VALUE));
            return row("ON_SALE", 0);
        });
        var service = summaryService(domain, identity);
        try (var ignored = ListingRagCredentialContext.open(credentials)) {
            assertEquals(RagErrorCode.AUTHENTICATION_FAILED,
                    assertThrows(RagException.class, () -> service.getListingSummary(11L)).code());
        }
        verify(identity, times(2)).requireReader(credentials);
    }
    @Test void memberSqlSearchIsNarrowedBeforeLimitAndRechecksReturnedRows() {
        var domain = mock(ListingDomainService.class); var identity = mock(ListingRagIdentity.class);
        when(identity.requireReader(any())).thenReturn(principal("BROKER"));
        when(domain.search(any())).thenReturn(List.of(row("ON_SALE",0), row("SOLD",0), row("ON_SALE",1)));
        var service = new ListingManagementServiceImpl(domain, new ListingConverter(), mock(ListingSearchService.class),
                new ListingKnowledgeDocumentAssembler(), mock(ListingRagOutbox.class), identity);
        try (var ignored = ListingRagCredentialContext.open(new RagCredentialContext("dedicated-access"))) {
            assertEquals(1, service.searchListingSummaries("region").size());
            verify(domain).search(argThat(r -> "ON_SALE".equals(r.status()) && "region".equals(r.keyword())));
            assertTrue(service.searchListings(new ListingQueryRequest(null,"OFFLINE",null,null,null,null,null,null)).isEmpty());
        }
    }
}
