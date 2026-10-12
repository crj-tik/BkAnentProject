package com.bkanent.listing.rag;

import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import com.bkanent.listing.converter.ListingConverter;
import com.bkanent.listing.entity.ListingEntity;
import com.bkanent.listing.mapper.ListingMapper;
import com.bkanent.listing.search.ListingKnowledgeDocumentAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingRagFacadeTest {
    static RagListingSearchRequest input(Integer keyword, Integer vector, Integer limit) {
        return new RagListingSearchRequest(1, "request", "滨江", null, null, null, null, null, null, keyword, vector, limit);
    }
    static RagCandidate candidate(long id) {
        return new RagCandidate(RagDocumentIds.listing(id), "LISTING", String.valueOf(id), "STALE PRIVATE CONTENT", 1, "1",
                List.of(RagRecallMode.KEYWORD, RagRecallMode.VECTOR), 1D, .8D, .03D, .7D, .7D,
                RagScoreKind.SEMANTIC_NORMALIZED, "semantic-v1", Map.of("address", RagValue.string("STALE")));
    }
    static RagSearchResponse response(RagSearchRequest req, List<RagCandidate> candidates) {
        return new RagSearchResponse(1, req.requestId(), req.spaceId(), "hybrid-v1", RagSearchMode.HYBRID_RERANK,
                RagSearchMode.HYBRID_RERANK, false, List.of(), candidates, List.of(new RagStageStats(RagStage.RERANK, candidates.size(), 1)));
    }
    static ListingRagOutbox revisions() {
        var outbox=mock(ListingRagOutbox.class);
        when(outbox.revisions(anyCollection())).thenAnswer(call -> {
            Map<Long,ListingRagOutbox.Revision> rows=new HashMap<>();
            for (Long id : (Collection<Long>)call.getArgument(0)) { rows.put(id,new ListingRagOutbox.Revision(1,false,"hash","event")); }
            return rows;
        });
        return outbox;
    }
    @Test void legacyDefault20Plus20Final10AndExplicitQuantitiesAreValidatedNotClamped() {
        var req = ListingRagFacade.request(input(null, null, null));
        assertEquals(20, req.recall().keywordCandidates()); assertEquals(20, req.recall().vectorCandidates());
        assertEquals(40, req.rerankCandidates()); assertEquals(10, req.candidateLimit()); assertTrue(req.semanticRerank());
        assertEquals(200, ListingRagFacade.request(input(200, 200, 200)).candidateLimit());
        assertThrows(RagException.class, () -> ListingRagFacade.request(input(20, 20, 41)));
        assertThrows(RagException.class, () -> ListingRagFacade.request(input(501, 20, 10)));
        assertEquals(500, ListingRagFacade.request(input(300, 300, 10)).rerankCandidates());
        var maximum = ListingRagFacade.request(input(500, 500, 200));
        assertEquals(500, maximum.recall().keywordCandidates());
        assertEquals(500, maximum.recall().vectorCandidates());
        assertEquals(500, maximum.rerankCandidates());
        assertEquals(200, maximum.candidateLimit());
        assertThrows(RagException.class, () -> ListingRagFacade.request(input(20, 20, -1)));
    }
    @Test void finalSQLStatusGrantAndFiltersEliminateStaleHintsAndNeverReturnTheirContent() {
        var identity = mock(ListingRagIdentity.class); var grants = mock(ListingRagGrantService.class); var mapper = mock(ListingMapper.class);
        when(identity.requireReader(any())).thenReturn(new AuthRagPrincipal(7L, "BROKER", true, true, Long.MAX_VALUE));
        when(grants.allowed(7L)).thenReturn(List.of("1", "2", "3", "4"));
        List<ListingEntity> rows = new ArrayList<>();
        for (long id=1; id<=5; id++) {
            ListingEntity e = new ListingEntity(); e.setId(id); e.setTitle("SQL " + id); e.setAddress("滨江路"); e.setStatus(id==2 ? "SOLD" : "ON_SALE"); e.setArea(BigDecimal.valueOf(id == 3 ? 20 : 100)); rows.add(e);
        }
        when(mapper.selectBatchIds(anyCollection())).thenReturn(rows);
        RagRetrievalRpcService rpc = (r,a,c,s) -> response(r, List.of(candidate(1),candidate(2),candidate(3),candidate(5)));
        var factory = new StaticListableBeanFactory(); factory.addBean("rpc", rpc);
        var facade = new ListingRagFacade(identity, grants, factory.getBeanProvider(RagRetrievalRpcService.class), mapper, new ListingConverter(), new ListingKnowledgeDocumentAssembler(), revisions());
        var req = new RagListingSearchRequest(1,"request","query","滨江",null,BigDecimal.valueOf(50),null,null,null,null,null,10);
        var result = facade.search(req, new RagCredentialContext("token"), "7");
        assertEquals(List.of(1L), result.matches().stream().map(m -> m.listing().id()).toList());
        assertTrue(result.context().contains("SQL 1")); assertFalse(result.context().contains("STALE"));
        assertFalse(result.matches().get(0).candidate().content().contains("STALE"));
        assertEquals(1, result.retrieval().candidates().size());
        verify(mapper, times(1)).selectBatchIds(anyCollection());
        assertThrows(RagException.class, () -> facade.search(req, new RagCredentialContext("token"), "8"));
    }
    @Test void atMostOneExpansionAndTotalCandidateBudgetAtMost500() {
        var identity = mock(ListingRagIdentity.class); var grants = mock(ListingRagGrantService.class); var mapper = mock(ListingMapper.class);
        when(identity.requireReader(any())).thenReturn(new AuthRagPrincipal(7L, "BROKER", true, true, Long.MAX_VALUE));
        when(grants.allowed(7L)).thenReturn(List.of("1")); when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        AtomicInteger calls = new AtomicInteger(); AtomicInteger budget = new AtomicInteger();
        RagRetrievalRpcService rpc = (r,a,c,s) -> {
            calls.incrementAndGet(); budget.addAndGet(r.recall().keywordCandidates()+r.recall().vectorCandidates());
            return response(r, java.util.stream.LongStream.rangeClosed(1, r.candidateLimit()).mapToObj(ListingRagFacadeTest::candidate).toList());
        };
        var factory = new StaticListableBeanFactory(); factory.addBean("rpc", rpc);
        var facade = new ListingRagFacade(identity,grants,factory.getBeanProvider(RagRetrievalRpcService.class),mapper,new ListingConverter(),new ListingKnowledgeDocumentAssembler(), revisions());
        assertTrue(facade.search(input(null,null,null),new RagCredentialContext("token"),"7").matches().isEmpty());
        assertEquals(2,calls.get()); assertTrue(budget.get()<=500); verify(mapper,times(2)).selectBatchIds(anyCollection());
    }
    @Test void explicitMaximumRouteCountsRemainUnchangedAndExhaustBudgetAfterOneCall() {
        var identity = mock(ListingRagIdentity.class); var grants = mock(ListingRagGrantService.class); var mapper = mock(ListingMapper.class);
        when(identity.requireReader(any())).thenReturn(new AuthRagPrincipal(7L, "BROKER", true, true, Long.MAX_VALUE));
        when(grants.allowed(7L)).thenReturn(List.of("1"));
        when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of());
        AtomicInteger calls = new AtomicInteger(); AtomicInteger budget = new AtomicInteger();
        RagRetrievalRpcService rpc = (request, authentication, credentials, scope) -> {
            calls.incrementAndGet();
            assertEquals(500, request.recall().keywordCandidates());
            assertEquals(500, request.recall().vectorCandidates());
            assertEquals(500, request.rerankCandidates());
            assertEquals(200, request.candidateLimit());
            budget.addAndGet(request.recall().keywordCandidates() + request.recall().vectorCandidates());
            return response(request, java.util.stream.LongStream.rangeClosed(1, 200).mapToObj(ListingRagFacadeTest::candidate).toList());
        };
        var factory = new StaticListableBeanFactory(); factory.addBean("rpc", rpc);
        var facade = new ListingRagFacade(identity, grants, factory.getBeanProvider(RagRetrievalRpcService.class), mapper,
                new ListingConverter(), new ListingKnowledgeDocumentAssembler(), revisions());
        assertTrue(facade.search(input(500, 500, 200), new RagCredentialContext("token"), "7").matches().isEmpty());
        assertEquals(1, calls.get());
        assertEquals(1000, budget.get());
        verify(mapper, times(1)).selectBatchIds(anyCollection());
    }
    @Test void revisionUpdateGrantRevokeAndOversizedProviderResponseFailClosed() {
        var identity=mock(ListingRagIdentity.class); var grants=mock(ListingRagGrantService.class); var mapper=mock(ListingMapper.class); var revisions=revisions();
        when(identity.requireReader(any())).thenReturn(new AuthRagPrincipal(7L,"BROKER",true,true,Long.MAX_VALUE));
        when(grants.allowed(7L)).thenReturn(List.of("1"));
        var e=new ListingEntity(); e.setId(1L); e.setStatus("ON_SALE"); e.setTitle("latest SQL");
        when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(e));
        var rpc=mock(RagRetrievalRpcService.class); when(rpc.search(any(),any(),any(),any())).thenAnswer(call->response(call.getArgument(0),List.of(candidate(1))));
        var factory=new StaticListableBeanFactory(); factory.addBean("rpc",rpc);
        var facade=new ListingRagFacade(identity,grants,factory.getBeanProvider(RagRetrievalRpcService.class),mapper,new ListingConverter(),new ListingKnowledgeDocumentAssembler(),revisions);
        when(revisions.revisions(anyCollection())).thenReturn(Map.of(1L,new ListingRagOutbox.Revision(2,false,"updated","event2")));
        assertTrue(facade.search(input(null,null,null),new RagCredentialContext("token"),"7").matches().isEmpty());
        when(revisions.revisions(anyCollection())).thenReturn(Map.of(1L,new ListingRagOutbox.Revision(1,false,"hash","event")));
        when(grants.allowed(7L)).thenReturn(List.of("2"));
        var revoked = facade.search(input(null,null,null),new RagCredentialContext("token"),"7");
        assertTrue(revoked.matches().isEmpty());
        assertEquals("", revoked.context());
        assertTrue(revoked.retrieval().candidates().isEmpty());
        assertTrue(revoked.retrieval().stageStats().isEmpty());
        doAnswer(call->response(call.getArgument(0),java.util.stream.LongStream.rangeClosed(1,11).mapToObj(ListingRagFacadeTest::candidate).toList())).when(rpc).search(any(),any(),any(),any());
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(RagException.class,()->facade.search(input(null,null,null),new RagCredentialContext("token"),"7")).code());
    }
    @Test void unavailableRetrievalIsNotEmptySuccessOrSQLRecallFallback() {
        var identity=mock(ListingRagIdentity.class); when(identity.requireReader(any())).thenReturn(new AuthRagPrincipal(7L,"BROKER",true,true,Long.MAX_VALUE));
        var mapper=mock(ListingMapper.class); var factory=new StaticListableBeanFactory();
        var facade=new ListingRagFacade(identity,mock(ListingRagGrantService.class),factory.getBeanProvider(RagRetrievalRpcService.class),mapper,new ListingConverter(),new ListingKnowledgeDocumentAssembler(), revisions());
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE,assertThrows(RagException.class,()->facade.search(input(null,null,null),new RagCredentialContext("token"),"7")).code());
        verifyNoInteractions(mapper);
    }
}
