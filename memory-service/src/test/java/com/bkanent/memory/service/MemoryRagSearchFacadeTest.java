package com.bkanent.memory.service;

import com.bkanent.common.agent.SystemConstraintRecord;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MemoryRagSearchFacadeTest {
    private MemoryRagSearchFacade facade(MemoryRagTestSupport f, RagRetrievalRpcService retrieval) { return new MemoryRagSearchFacade(retrieval, f.security, f.jdbc, f.properties, f.projection); }
    private MemoryRagSearchRequest request(int topK, Double threshold, Double halfLife, List<RagFilter> filters) {
        return new MemoryRagSearchRequest(new RagSearchRequest(1, "request", "memory.preference", "deployment", filters, null, null, null, null, 50, null), topK, threshold, halfLife);
    }
    @Test
    void finalRankingConsumesWholePoolAndSoftDecayIsNotHardTimeFilter() {
        var f = new MemoryRagTestSupport();
        var old = MemoryRagTestSupport.preference("old", ".9", LocalDateTime.now().minusDays(40));
        var fresh = MemoryRagTestSupport.preference("fresh", ".9", LocalDateTime.now());
        f.upsert(old); f.upsert(fresh);
        RagRetrievalRpcService retrieval = (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(f.candidate(old, .95, RagScoreKind.SEMANTIC_NORMALIZED), f.candidate(fresh, .75, RagScoreKind.SEMANTIC_NORMALIZED)));
        var result = facade(f, retrieval).search(request(1, .7, 30.0, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals("fresh", result.matches().get(0).preference().preferenceKey());
        var oldStillEligible = facade(f, retrieval).search(request(2, .7, 30.0, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(2, oldStillEligible.matches().size());
        var cutoff = RagValue.instant(LocalDateTime.now().minusDays(10).toInstant(java.time.ZoneOffset.UTC));
        var hard = facade(f, retrieval).search(request(2, .7, null, List.of(new RagFilter("lastObservedAt", RagFilterOperator.GTE, cutoff))), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(1, hard.matches().size());
        assertEquals(1, hard.retrieval().candidates().size());
    }
    @Test
    void nonSemanticRankCannotBeUsedAsSemanticThresholdAndUnregisteredVersionFails() {
        var f = new MemoryRagTestSupport();
        var pref = MemoryRagTestSupport.preference("key", ".9", LocalDateTime.now()); f.upsert(pref);
        var ranked = facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(f.candidate(pref, .95, RagScoreKind.RANK_FUSION))));
        var ex = assertThrows(RagException.class, () -> ranked.search(request(1, .8, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials));
        assertEquals(RagErrorCode.SCORING_PROFILE_MISMATCH, ex.code());
        assertEquals(1, ranked.search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials).matches().size());
        f.properties.setSemanticScoringVersions(java.util.Set.of());
        assertThrows(RagException.class, () -> facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(f.candidate(pref, .95, RagScoreKind.SEMANTIC_NORMALIZED)))).search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials));
    }
    @Test
    void sourceUpdateAfterRpcAndThresholdRejectionNeverLeakOriginalBodyInEitherResponse() {
        var f = new MemoryRagTestSupport();
        var pref = MemoryRagTestSupport.preference("stale", ".9", LocalDateTime.now()); f.upsert(pref);
        var stale = f.candidate(pref, .9, RagScoreKind.SEMANTIC_NORMALIZED);
        var calls = new AtomicInteger();
        var result = facade(f, (r, a, c, s) -> { calls.incrementAndGet(); f.tx.executeWithoutResult(tx -> f.preferences.decayConfidence("42", null)); return MemoryRagTestSupport.response(r, List.of(stale)); }).search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertTrue(result.matches().isEmpty()); assertTrue(result.retrieval().candidates().isEmpty()); assertEquals(2, calls.get());
        var below = f.candidate(MemoryRagTestSupport.preference("stale", ".8", LocalDateTime.now()), .1, RagScoreKind.SEMANTIC_NORMALIZED);
        var low = facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(below))).search(request(1, .8, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertTrue(low.matches().isEmpty()); assertTrue(low.retrieval().candidates().isEmpty());
    }
    @Test
    void finalCandidateBodyAndMetadataAreRebuiltFromSqlNotReturnedPayload() {
        var f = new MemoryRagTestSupport(); var pref = MemoryRagTestSupport.preference("key", ".9", LocalDateTime.now()); f.upsert(pref);
        var original = f.candidate(pref, .9, RagScoreKind.SEMANTIC_NORMALIZED);
        var malicious = new RagCandidate(original.documentId(), original.sourceType(), original.sourceId(), "OLD PRIVATE BODY", original.projectionVersion(), original.schemaVersion(), original.sources(), null, null, null, .9, .9, original.scoreKind(), original.scoringVersion(), Map.of("preferenceKey", RagValue.string("key"), "unregistered", RagValue.string("PRIVATE")));
        var result = facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(malicious))).search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(1, result.matches().size());
        assertFalse(result.matches().get(0).candidate().content().contains("OLD PRIVATE"));
        assertFalse(result.retrieval().candidates().get(0).metadata().containsKey("unregistered"));
        f.jdbc.update("UPDATE user_preference_memory SET preference_value=? WHERE preference_key=?", "out of band", "key");
        var mismatch = facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(malicious))).search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertTrue(mismatch.retrieval().candidates().isEmpty());
    }
    @Test
    void constraintDisabledAfterRpcNeverAppearsInSafeResponse() {
        var f = new MemoryRagTestSupport();
        var active = new SystemConstraintRecord("rule", "private rule", "policy", "manual", "tags", true);
        f.tx.executeWithoutResult(tx -> f.constraints.upsert(active));
        var event = f.latest(RagDocumentIds.constraint("rule"));
        var candidate = new RagCandidate(event.documentId(), "system_constraint", "rule", "private rule", event.revision(), "memory-v1", List.of(RagRecallMode.VECTOR), null, null, null, .9, .9, RagScoreKind.SEMANTIC_NORMALIZED, "semantic-test-v1", Map.of());
        var request = new MemoryRagSearchRequest(RagSearchRequest.defaults("constraint", "memory.constraint", "rule"), 1, null, null);
        var result = facade(f, (r, a, c, s) -> { f.tx.executeWithoutResult(tx -> f.constraints.upsert(new SystemConstraintRecord("rule", "private rule", "policy", "manual", "tags", false))); return MemoryRagTestSupport.response(r, List.of(candidate)); }).search(request, MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertTrue(result.matches().isEmpty()); assertTrue(result.retrieval().candidates().isEmpty());
    }
    @Test
    void ownerFilterIsSignedAndSqlFinalLookupCannotReturnOtherUser() {
        var f = new MemoryRagTestSupport(); var pref = MemoryRagTestSupport.preference("key", ".9", LocalDateTime.now()); f.upsert(pref);
        var result = facade(f, (r, a, c, scope) -> { assertEquals("42", scope.ownerId()); assertEquals("memory-service", scope.ownerDomain()); assertTrue(scope.activeOnly()); assertEquals(RagActorMode.USER, a.actorMode()); return MemoryRagTestSupport.response(r, List.of(f.candidate(pref, .9, RagScoreKind.SEMANTIC_NORMALIZED))); }).search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(1, result.matches().size());
        assertThrows(RagException.class, () -> facade(f, (r, a, c, s) -> { fail("must not retrieve"); return null; }).search(request(1, null, null, List.of(new RagFilter("ownerId", RagFilterOperator.EQ, RagValue.string("other")))), MemoryRagTestSupport.principal("ADMIN"), f.credentials));
    }
}
