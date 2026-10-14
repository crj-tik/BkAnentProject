package com.bkanent.memory.service;

import com.bkanent.common.agent.SystemConstraintRecord;
import com.bkanent.common.agent.UserPreferenceRecord;
import com.bkanent.common.rag.*;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import com.bkanent.memory.config.MemoryRagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryRagSearchFacadeTest {
    private MemoryRagSearchFacade facade(MemoryRagTestSupport f, RagRetrievalRpcService retrieval) { return new MemoryRagSearchFacade(retrieval, f.security, f.jdbc, f.properties, f.projection); }
    private MemoryRagSearchRequest request(int topK, Double threshold, Double halfLife, List<RagFilter> filters) {
        return new MemoryRagSearchRequest(new RagSearchRequest(1, "request", "memory.preference", "deployment", filters, null, null, null, null, 50, null), topK, threshold, halfLife);
    }
    private static final Instant AS_OF = Instant.parse("2030-01-31T12:00:00Z");
    private static LocalDateTime observed(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    // LR-43 verified identity fixture, not automatic runtime registration or deployment evidence.
    private static final String GOVERNED_SCORING_VERSION = "12ab2575eb91fb134ec2d616b872f37578457fe88258ce2906609bcdc63db88b";
    private static final String SCORING_ENV = "MEMORY_RAG_SEMANTIC_SCORING_VERSIONS";
    private static final String SCORING_PROPERTY = "memory.rag.semantic-scoring-versions";

    private static java.nio.file.Path repositoryFile(String relative) {
        var path = java.nio.file.Path.of(relative);
        return java.nio.file.Files.exists(path) ? path : java.nio.file.Path.of("..").resolve(relative);
    }

    private static MemoryRagProperties configuredScoring(String template, String versions) throws Exception {
        var environment = new MockEnvironment(); // No host/root .env or real Nacos/secret access.
        var source = new YamlPropertySourceLoader().load("memory-template",
                new FileSystemResource(repositoryFile(template))).get(0);
        assertEquals("${" + SCORING_ENV + ":}", source.getProperty(SCORING_PROPERTY));
        environment.getPropertySources().addFirst(source);
        if (versions != null) environment.setProperty(SCORING_ENV, versions);
        return Binder.get(environment).bind("memory.rag", Bindable.of(MemoryRagProperties.class)).get();
    }

    @Test
    void scoringRegistrationTemplatesBindOnlyExplicitVersionsAndDefaultEmpty() throws Exception {
        for (String template : List.of("memory-service/src/main/resources/application.yml", "nacos/memory-service.yaml")) {
            assertTrue(configuredScoring(template, null).getSemanticScoringVersions().isEmpty());
            assertTrue(configuredScoring(template, "").getSemanticScoringVersions().isEmpty());
            assertEquals(java.util.Set.of(GOVERNED_SCORING_VERSION), configuredScoring(template, GOVERNED_SCORING_VERSION).getSemanticScoringVersions());
            assertEquals(java.util.Set.of(GOVERNED_SCORING_VERSION, "other-explicit-verified-version"),
                    configuredScoring(template, GOVERNED_SCORING_VERSION + ",other-explicit-verified-version").getSemanticScoringVersions());
        }
    }

    @Test
    void composeAndEnvExampleKeepMemoryRegistrationEmptyDespiteGovernedProfiles() throws Exception {
        Map<?, ?> compose = new Yaml().load(java.nio.file.Files.readString(repositoryFile("docker-compose.yml")));
        var services = (Map<?, ?>) compose.get("services");
        var memory = (Map<?, ?>) services.get("memory-service");
        var environment = (Map<?, ?>) memory.get("environment");
        assertEquals("${" + SCORING_ENV + ":-}", environment.get(SCORING_ENV));
        assertFalse(((Map<?, ?>) ((Map<?, ?>) services.get("rag-service")).get("environment")).containsKey(SCORING_ENV));
        String example = java.nio.file.Files.readString(repositoryFile(".env.example"));
        assertEquals(List.of(SCORING_ENV + "="), example.lines().filter(line -> line.startsWith(SCORING_ENV + "=")).toList());
        assertTrue(example.contains("# " + GOVERNED_SCORING_VERSION));
        Map<?, ?> rag = new Yaml().load(java.nio.file.Files.readString(repositoryFile("nacos/rag-service.yaml")));
        var spaces = (Map<?, ?>) ((Map<?, ?>) rag.get("rag")).get("spaces");
        for (String space : List.of("[listing.knowledge]", "[memory.preference]", "[memory.constraint]")) {
            var profile = (Map<?, ?>) ((Map<?, ?>) spaces.get(space)).get("rerank-profile");
            assertEquals("governed", profile.get("provider"));
            assertEquals("LOGIT", profile.get("scale"));
            assertEquals(GOVERNED_SCORING_VERSION, profile.get("scoring-version"));
        }
    }

    @Test
    void configuredExactGovernedVersionAcceptsNormalizedScoreButEmptyWildcardOrDriftRejects() throws Exception {
        var f = new MemoryRagTestSupport();
        var preference = MemoryRagTestSupport.preference("governed", ".9", observed(AS_OF));
        f.upsert(preference);
        var original = f.candidate(preference, .8, RagScoreKind.SEMANTIC_NORMALIZED);
        var candidate = scoring(original, RagScoreKind.SEMANTIC_NORMALIZED, GOVERNED_SCORING_VERSION, .8);
        for (String template : List.of("memory-service/src/main/resources/application.yml", "nacos/memory-service.yaml")) {
            var search = facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(candidate)));
            for (String registration : java.util.Arrays.asList(null, "", "*")) {
                f.properties.setSemanticScoringVersions(configuredScoring(template, registration).getSemanticScoringVersions());
                assertEquals(RagErrorCode.SCORING_PROFILE_MISMATCH, assertThrows(RagException.class, () ->
                        search.search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials)).code());
            }
            f.properties.setSemanticScoringVersions(configuredScoring(template, GOVERNED_SCORING_VERSION).getSemanticScoringVersions());
            var result = search.search(request(1, .8, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
            assertEquals(1, result.matches().size());
            assertEquals(GOVERNED_SCORING_VERSION, result.matches().get(0).candidate().scoringVersion());
            assertEquals(.72, result.matches().get(0).domainScore(), 1e-12); // Uses normalized relevance, not raw LOGIT.
            for (String drift : java.util.Arrays.asList(null, "semantic-test-v1", GOVERNED_SCORING_VERSION + "-drift")) {
                var invalid = scoring(original, RagScoreKind.SEMANTIC_NORMALIZED, drift, .8);
                assertEquals(RagErrorCode.SCORING_PROFILE_MISMATCH, assertThrows(RagException.class, () ->
                        facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(invalid)))
                                .search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials)).code());
            }
        }
    }

    @Test
    void finalRankingConsumesWholePoolAndSoftDecayIsNotHardTimeFilter() {
        var f = new MemoryRagTestSupport();
        var old = MemoryRagTestSupport.preference("old", ".9", observed(AS_OF.minusSeconds(40 * 86400L)));
        var fresh = MemoryRagTestSupport.preference("fresh", ".9", observed(AS_OF));
        f.upsert(old); f.upsert(fresh);
        RagRetrievalRpcService retrieval = (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(f.candidate(old, .95, RagScoreKind.SEMANTIC_NORMALIZED), f.candidate(fresh, .75, RagScoreKind.SEMANTIC_NORMALIZED)));
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(AS_OF);
            var result = facade(f, retrieval).search(request(1, .7, 30.0, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
            assertEquals("fresh", result.matches().get(0).preference().preferenceKey());
            var oldStillEligible = facade(f, retrieval).search(request(2, .7, 30.0, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
            assertEquals(2, oldStillEligible.matches().size());
            var cutoff = RagValue.instant(AS_OF.minusSeconds(10 * 86400L));
            var hard = facade(f, retrieval).search(request(2, .7, null, List.of(new RagFilter("lastObservedAt", RagFilterOperator.GTE, cutoff))), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
            assertEquals(1, hard.matches().size());
            assertEquals(1, hard.retrieval().candidates().size());
        }
    }
    @Test
    void nonSemanticRankCannotBeUsedAsSemanticThresholdAndUnregisteredVersionFails() {
        var f = new MemoryRagTestSupport();
        var pref = MemoryRagTestSupport.preference("key", ".9", LocalDateTime.now()); f.upsert(pref);
        var ranked = facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(f.candidate(pref, .95, RagScoreKind.RANK_FUSION))));
        var ex = assertThrows(RagException.class, () -> ranked.search(request(1, .8, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials));
        assertEquals(RagErrorCode.SCORING_PROFILE_MISMATCH, ex.code());
        assertEquals(1, ranked.search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials).matches().size());
        f.properties.setSemanticScoringVersions(null);
        assertEquals(RagErrorCode.SCORING_PROFILE_MISMATCH, assertThrows(RagException.class, () -> facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(f.candidate(pref, .95, RagScoreKind.SEMANTIC_NORMALIZED)))).search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials)).code());
    }
    @Test
    void sourceUpdateAfterRpcAndThresholdRejectionNeverLeakOriginalBodyInEitherResponse() {
        var f = new MemoryRagTestSupport();
        var pref = MemoryRagTestSupport.preference("stale", ".9", LocalDateTime.now()); f.upsert(pref);
        var stale = f.candidate(pref, .9, RagScoreKind.SEMANTIC_NORMALIZED);
        var calls = new AtomicInteger();
        var result = facade(f, (r, a, c, s) -> { calls.incrementAndGet(); f.tx.executeWithoutResult(tx -> f.preferences.decayConfidence("42", null)); return MemoryRagTestSupport.response(r, List.of(stale)); }).search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertTrue(result.matches().isEmpty()); assertTrue(result.retrieval().candidates().isEmpty()); assertEquals(1, calls.get());
        var below = f.candidate(MemoryRagTestSupport.preference("stale", ".8", LocalDateTime.now()), .1, RagScoreKind.SEMANTIC_NORMALIZED);
        var low = facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(below))).search(request(1, .8, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertTrue(low.matches().isEmpty()); assertTrue(low.retrieval().candidates().isEmpty());
    }
    @Test
    void finalCandidateBodyAndMetadataAreRebuiltFromSqlNotReturnedPayload() {
        var f = new MemoryRagTestSupport();
        var pref = MemoryRagTestSupport.preference("key", ".9", observed(AS_OF.plusNanos(123456700))); f.upsert(pref);
        var original = f.candidate(pref, .9, RagScoreKind.SEMANTIC_NORMALIZED);
        var malicious = new RagCandidate(original.documentId(), original.sourceType(), original.sourceId(), "OLD PRIVATE BODY", original.projectionVersion(), original.schemaVersion(), original.sources(), null, null, null, .9, .9, original.scoreKind(), original.scoringVersion(), Map.of("preferenceKey", RagValue.string("key"), "unregistered", RagValue.string("PRIVATE"),
                "ownerId", RagValue.string("other"), "userId", RagValue.string("other"), "confidence", RagValue.number(.1)));
        var result = facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(malicious))).search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(1, result.matches().size());
        assertFalse(result.matches().get(0).candidate().content().contains("OLD PRIVATE"));
        assertEquals(Map.of("preferenceKey", RagValue.string("key"), "category", RagValue.string("deployment"),
                "confidence", RagValue.number(new BigDecimal(".9")), "observationCount", RagValue.number(1),
                "lastObservedAt", RagValue.instant(AS_OF.plusNanos(123457000)), "active", RagValue.bool(true)),
                result.retrieval().candidates().get(0).metadata());
        assertEquals(result.matches().get(0).candidate(), result.retrieval().candidates().get(0));
        assertFalse(result.retrieval().candidates().get(0).content().contains("private evidence"));
        assertFalse(result.retrieval().candidates().get(0).content().contains("private session"));
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
    void declaredStrategyUsesPersistedConfidenceAndExactHalfLifeBeforeFinalTopK() {
        var f = new MemoryRagTestSupport();
        var pref = MemoryRagTestSupport.preference("rounded", ".812345", observed(AS_OF.minusSeconds(30 * 86400L)));
        f.upsert(pref);
        var candidate = f.candidate(pref, .8, RagScoreKind.SEMANTIC_NORMALIZED);
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(AS_OF);
            var principal = MemoryRagTestSupport.principal("ADMIN");
            var result = facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(candidate)))
                    .search(request(1, .8, 30.0, List.of()), principal, f.credentials);
            assertEquals("memory-confidence-semantic-decay-v1", result.domainScoringVersion());
            assertEquals(1, result.matches().size()); // Threshold precedes confidence and decay.
            var match = result.matches().get(0);
            assertEquals(new BigDecimal(".8123"), match.preference().confidence());
            assertEquals(.8 * .8123 * .5, match.domainScore(), 1e-12);
            assertEquals(candidate.rawRerankScore(), match.candidate().rawRerankScore());
            assertEquals(candidate.relevanceScore(), match.candidate().relevanceScore());
            assertEquals(candidate.scoringVersion(), match.candidate().scoringVersion());
            assertEquals("rrf-v1", result.retrieval().strategyVersion());
        }
    }

    @Test
    void equalScoresPreserveUpstreamOrderWithExactlyOneScoringInstant() {
        var f = new MemoryRagTestSupport();
        var a = MemoryRagTestSupport.preference("a", ".9", observed(AS_OF.minusSeconds(86400)));
        var z = MemoryRagTestSupport.preference("z", ".9", a.lastObservedAt());
        f.upsert(a); f.upsert(z);
        var candidates = List.of(f.candidate(z, .8, RagScoreKind.SEMANTIC_NORMALIZED), f.candidate(a, .8, RagScoreKind.SEMANTIC_NORMALIZED));
        assertTrue(candidates.get(1).documentId().compareTo(candidates.get(0).documentId()) < 0);
        var security = mock(MemoryRagSecurity.class); // Exclude signing clocks from scoring-clock count.
        var search = new MemoryRagSearchFacade((r, auth, c, s) -> MemoryRagTestSupport.response(r, candidates), security, f.jdbc, f.properties, f.projection);
        var principal = MemoryRagTestSupport.principal("ADMIN");
        org.mockito.Mockito.when(security.authenticate(f.credentials)).thenReturn(principal);
        Instant later = AS_OF.plusSeconds(86400);
        for (int iteration = 0; iteration < 3; iteration++) {
            try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
                time.when(Instant::now).thenReturn(AS_OF, later);
                var result = search.search(request(2, null, 1.0, List.of()), principal, f.credentials);
                assertEquals(List.of("z", "a"), result.matches().stream().map(m -> m.preference().preferenceKey()).toList());
                assertEquals(.36, result.matches().get(0).domainScore(), 1e-12);
                assertEquals(result.matches().get(0).domainScore(), result.matches().get(1).domainScore());
                assertEquals(.36, result.matches().get(1).domainScore(), 1e-12);
            }
        }
    }

    @Test
    void hardTimeBoundsAreInclusiveAndIndependentOfMissingFutureAndSoftDecay() {
        var f = new MemoryRagTestSupport();
        Instant lower = AS_OF.minusSeconds(30 * 86400L);
        var records = List.of(
                MemoryRagTestSupport.preference("lower", ".8", observed(lower)),
                MemoryRagTestSupport.preference("upper", ".8", observed(AS_OF)),
                MemoryRagTestSupport.preference("outside", ".8", observed(lower.minusSeconds(1))),
                MemoryRagTestSupport.preference("future", ".8", observed(AS_OF.plusSeconds(86400))),
                MemoryRagTestSupport.preference("missing", ".8", null));
        records.forEach(f::upsert);
        var candidates = records.stream().map(p -> f.candidate(p, .75, RagScoreKind.SEMANTIC_NORMALIZED)).toList();
        var filters = List.of(new RagFilter("lastObservedAt", RagFilterOperator.GTE, RagValue.instant(lower)),
                new RagFilter("lastObservedAt", RagFilterOperator.LTE, RagValue.instant(AS_OF)));
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenReturn(AS_OF);
            var soft = facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, candidates))
                    .search(request(5, null, 30.0, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
            assertEquals(5, soft.matches().size());
            for (var match : soft.matches()) {
                if (List.of("future", "missing").contains(match.preference().preferenceKey())) assertEquals(.6, match.domainScore(), 1e-12);
            }
            var hard = facade(f, (r, a, c, s) -> {
                assertEquals(filters, r.filters());
                assertTrue(r.recall().hybrid());
                return MemoryRagTestSupport.response(r, candidates);
            }).search(request(5, null, 30.0, filters), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
            assertEquals(List.of("upper", "lower"), hard.matches().stream().map(m -> m.preference().preferenceKey()).toList());
            assertEquals(.3, hard.matches().get(1).domainScore(), 1e-12);
            assertEquals(2, hard.retrieval().candidates().size());
        }
    }

    @Test
    void candidateLimitIsNotRewrittenToDomainTopK() {
        var f = new MemoryRagTestSupport();
        var candidates = new ArrayList<RagCandidate>();
        for (int i = 0; i < 6; i++) {
            var pref = MemoryRagTestSupport.preference("pool-" + i, i == 5 ? ".9" : ".2", observed(AS_OF));
            f.upsert(pref);
            candidates.add(f.candidate(pref, .9, RagScoreKind.SEMANTIC_NORMALIZED));
        }
        var result = facade(f, (r, a, c, s) -> {
            assertEquals(50, r.candidateLimit());
            assertEquals(100, r.rerankCandidates());
            return MemoryRagTestSupport.response(r, candidates);
        }).search(request(5, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(5, result.matches().size());
        assertEquals("pool-5", result.matches().get(0).preference().preferenceKey());
        assertEquals(result.matches().stream().map(MemoryRagMatch::candidate).toList(), result.retrieval().candidates());
    }

    private RagCandidate scoring(RagCandidate candidate, RagScoreKind kind, String version, Double relevance) {
        return new RagCandidate(candidate.documentId(), candidate.sourceType(), candidate.sourceId(), candidate.content(),
                candidate.projectionVersion(), candidate.schemaVersion(), candidate.sources(), 9999.0, .001, .99, -12.0,
                relevance, kind, version, candidate.metadata());
    }

    @Test
    void confidenceHardBoundsUsePersistedSqlDecimalNotReturnedMetadata() {
        var f = new MemoryRagTestSupport();
        var rounded = MemoryRagTestSupport.preference("boundary", ".812345", observed(AS_OF));
        var outside = MemoryRagTestSupport.preference("outside", ".8124", observed(AS_OF));
        f.upsert(rounded); f.upsert(outside);
        var filters = List.of(new RagFilter("confidence", RagFilterOperator.GTE, RagValue.number(new BigDecimal(".8123"))),
                new RagFilter("confidence", RagFilterOperator.LTE, RagValue.number(new BigDecimal(".8123"))));
        var result = facade(f, (r, a, c, s) -> {
            assertEquals(filters, r.filters());
            return MemoryRagTestSupport.response(r, List.of(f.candidate(outside, .9, RagScoreKind.SEMANTIC_NORMALIZED),
                    f.candidate(rounded, .9, RagScoreKind.SEMANTIC_NORMALIZED)));
        }).search(request(2, null, null, filters), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(1, result.matches().size());
        assertEquals("boundary", result.matches().get(0).preference().preferenceKey());
        assertEquals(RagValue.number(new BigDecimal(".8123")), result.retrieval().candidates().get(0).metadata().get("confidence"));
    }

    @Test
    void unknownOrMissingSemanticFieldsFailClosedAndRankFallbackIgnoresRawScores() {
        var f = new MemoryRagTestSupport();
        var pref = MemoryRagTestSupport.preference("scoring", ".9", observed(AS_OF)); f.upsert(pref);
        var original = f.candidate(pref, .8, RagScoreKind.SEMANTIC_NORMALIZED);
        var invalid = List.of(scoring(original, RagScoreKind.SEMANTIC_NORMALIZED, "unknown-v9", .8),
                scoring(original, RagScoreKind.SEMANTIC_NORMALIZED, null, .8),
                scoring(original, RagScoreKind.SEMANTIC_NORMALIZED, "semantic-test-v1", null));
        for (var candidate : invalid) {
            assertEquals(RagErrorCode.SCORING_PROFILE_MISMATCH, assertThrows(RagException.class, () ->
                    facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(candidate)))
                            .search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials)).code());
        }
        for (var kind : List.of(RagScoreKind.RANK_SINGLE, RagScoreKind.RANK_FUSION)) {
            var candidate = scoring(original, kind, "rank-v1", .01);
            var search = facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(candidate)));
            assertEquals(RagErrorCode.SCORING_PROFILE_MISMATCH, assertThrows(RagException.class, () ->
                    search.search(request(1, .1, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials)).code());
            assertEquals(.9, search.search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials).matches().get(0).domainScore(), 1e-12);
        }
        var missingKind = scoring(original, null, "semantic-test-v1", .8);
        assertEquals(RagErrorCode.SCORING_PROFILE_MISMATCH, assertThrows(RagException.class, () ->
                facade(f, (r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(missingKind)))
                        .search(request(1, .1, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials)).code());
    }

    private MemoryRagSearchRequest poolRequest(int limit, int topK, RagRecallOptions recall, Double halfLife) {
        return new MemoryRagSearchRequest(new RagSearchRequest(1, "pool", "memory.preference", "q", List.of(), recall,
                null, null, null, limit, null), topK, null, halfLife);
    }

    private RagCandidate absent(String key) {
        String id = RagDocumentIds.preference("42", key);
        return new RagCandidate(id, "user_preference", id, "PRIVATE STALE", 1, "memory-v1", List.of(RagRecallMode.VECTOR),
                null, null, null, .9, .9, RagScoreKind.SEMANTIC_NORMALIZED, "semantic-test-v1", Map.of("preferenceKey", RagValue.string(key)));
    }

    @Test
    void expandedPoolRetainsFirstSurvivorAndUsesLaterDuplicateWithLatestResponseTieOrder() {
        var f = new MemoryRagTestSupport();
        var first = MemoryRagTestSupport.preference("first-only", ".9", observed(AS_OF));
        var duplicate = MemoryRagTestSupport.preference("duplicate", ".9", observed(AS_OF));
        var next = MemoryRagTestSupport.preference("next", ".9", observed(AS_OF));
        f.upsert(first); f.upsert(duplicate); f.upsert(next);
        var calls = new AtomicInteger();
        var requests = new ArrayList<RagSearchRequest>();
        var result = facade(f, (r, a, c, s) -> {
            requests.add(r);
            if (calls.incrementAndGet() == 1) return MemoryRagTestSupport.response(r, List.of(
                    f.candidate(first, .8, RagScoreKind.SEMANTIC_NORMALIZED), f.candidate(duplicate, .1, RagScoreKind.SEMANTIC_NORMALIZED), absent("missing")));
            assertEquals(6, r.candidateLimit());
            return MemoryRagTestSupport.response(r, List.of(f.candidate(next, .8, RagScoreKind.SEMANTIC_NORMALIZED),
                    f.candidate(duplicate, .8, RagScoreKind.SEMANTIC_NORMALIZED)));
        }).search(poolRequest(3, 3, null, null), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(2, calls.get());
        assertEquals(List.of("next", "duplicate", "first-only"), result.matches().stream().map(m -> m.preference().preferenceKey()).toList());
        assertEquals(.8, result.matches().get(1).candidate().relevanceScore());
        assertEquals(3, result.retrieval().candidates().stream().map(RagCandidate::documentId).distinct().count());
        assertEquals(requests.get(1).requestId(), result.retrieval().requestId());
        assertNotEquals(requests.get(0).requestId(), requests.get(1).requestId());
        assertTrue(requests.stream().mapToInt(r -> r.recall().capacity()).sum() <= 500);
    }

    @Test
    void secondRpcRechecksAllMergedSqlTruthAndSharesOriginalScoringSnapshot() {
        var f = new MemoryRagTestSupport();
        var removed = MemoryRagTestSupport.preference("removed", ".9", observed(AS_OF));
        var updated = MemoryRagTestSupport.preference("updated", ".9", observed(AS_OF));
        var retained = MemoryRagTestSupport.preference("retained", ".9", observed(AS_OF));
        f.upsert(removed); f.upsert(updated); f.upsert(retained);
        var oldUpdated = f.candidate(updated, .9, RagScoreKind.SEMANTIC_NORMALIZED);
        var clock = new AtomicReference<>(AS_OF);
        var calls = new AtomicInteger();
        try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            time.when(Instant::now).thenAnswer(invocation -> clock.get());
            var principal = MemoryRagTestSupport.principal("ADMIN");
            var result = facade(f, (r, a, c, s) -> {
                if (calls.incrementAndGet() == 1) return MemoryRagTestSupport.response(r, List.of(
                        f.candidate(removed, .9, RagScoreKind.SEMANTIC_NORMALIZED), oldUpdated,
                        f.candidate(retained, .8, RagScoreKind.SEMANTIC_NORMALIZED), absent("missing")));
                clock.set(AS_OF.plusSeconds(30 * 86400L));
                f.upsert(MemoryRagTestSupport.preference("removed", ".05", observed(AS_OF)));
                f.upsert(MemoryRagTestSupport.preference("updated", ".8", observed(AS_OF)));
                return MemoryRagTestSupport.response(r, List.of(f.candidate(updated, .7, RagScoreKind.SEMANTIC_NORMALIZED)));
            }).search(poolRequest(4, 4, null, 30.0), principal, f.credentials);
            assertEquals(2, calls.get());
            assertEquals(List.of("retained", "updated"), result.matches().stream().map(m -> m.preference().preferenceKey()).toList());
            assertEquals(.72, result.matches().get(0).domainScore(), 1e-12);
            assertEquals(.56, result.matches().get(1).domainScore(), 1e-12);
            assertTrue(result.matches().get(1).candidate().projectionVersion() > oldUpdated.projectionVersion());
            assertEquals(result.matches().stream().map(MemoryRagMatch::candidate).toList(), result.retrieval().candidates());
        }
    }

    @Test
    void cumulativeBudgetCountsOnlySelectedRoutesAndPreservesExplicitInitialQuantities() {
        var f = new MemoryRagTestSupport();
        var options = List.of(new RagRecallOptions(true, null, 50, 50), new RagRecallOptions(true, null, 125, 125),
                new RagRecallOptions(true, null, 126, 125), new RagRecallOptions(true, null, 500, 500),
                new RagRecallOptions(false, RagRecallMode.KEYWORD, 250, null),
                new RagRecallOptions(false, RagRecallMode.KEYWORD, 251, null),
                new RagRecallOptions(false, RagRecallMode.VECTOR, null, 250),
                new RagRecallOptions(false, RagRecallMode.VECTOR, null, 251));
        for (var recall : options) {
            var requests = new ArrayList<RagSearchRequest>();
            var result = facade(f, (r, a, c, s) -> {
                requests.add(r);
                return MemoryRagTestSupport.response(r, requests.size() == 1 ? List.of(absent("a"), absent("b")) : List.of());
            }).search(poolRequest(2, 2, recall, null), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
            assertEquals(recall, requests.get(0).recall());
            int initial = recall.capacity();
            assertEquals(initial <= 250 ? 2 : 1, requests.size());
            assertTrue(requests.stream().mapToInt(r -> r.recall().capacity()).sum() <= Math.max(500, initial));
            assertTrue(result.matches().isEmpty());
        }
    }

    @Test
    void shortInitialPoolDoesNotExpandEvenWhenSqlRejectsEveryCandidate() {
        var f = new MemoryRagTestSupport();
        var calls = new AtomicInteger();
        var result = facade(f, (r, a, c, s) -> {
            calls.incrementAndGet();
            return MemoryRagTestSupport.response(r, List.of(absent("missing")));
        }).search(poolRequest(2, 2, null, null), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(1, calls.get());
        assertTrue(result.matches().isEmpty());
    }

    @Test
    void constraintPublicMetadataIsExactlyTheDeclaredFourTypedFields() {
        var f = new MemoryRagTestSupport();
        var constraint = new SystemConstraintRecord("rule", "current text", "policy", "private source", "tag-a,tag-b", true);
        f.tx.executeWithoutResult(tx -> f.constraints.upsert(constraint));
        var event = f.latest(RagDocumentIds.constraint("rule"));
        var candidate = new RagCandidate(event.documentId(), "system_constraint", "rule", "PRIVATE STALE", event.revision(), "memory-v1",
                List.of(RagRecallMode.VECTOR), null, .5, .5, .9, .9, RagScoreKind.SEMANTIC_NORMALIZED, "semantic-test-v1",
                Map.of("source", RagValue.string("PRIVATE"), "ownerId", RagValue.string("other")));
        var domain = new MemoryRagSearchRequest(RagSearchRequest.defaults("constraint", "memory.constraint", "rule"), 1, null, null);
        var result = facade(f, (r, a, c, s) -> {
            var response = MemoryRagTestSupport.response(r, List.of(candidate));
            return new RagSearchResponse(1, response.requestId(), response.spaceId(), response.strategyVersion(), response.requestedMode(),
                    response.effectiveMode(), false, List.of(), response.candidates(), List.of(new RagStageStats(RagStage.RERANK, 99, 1)));
        }).search(domain, MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(Map.of("constraintKey", RagValue.string("rule"), "category", RagValue.string("policy"),
                "tags", RagValue.string("tag-a,tag-b"), "active", RagValue.bool(true)), result.retrieval().candidates().get(0).metadata());
        assertEquals(result.matches().get(0).candidate(), result.retrieval().candidates().get(0));
        assertFalse(result.retrieval().candidates().get(0).content().contains("PRIVATE"));
        assertTrue(result.retrieval().stageStats().isEmpty());
    }

    @Test
    void invalidRetrievalResponseBindingsFailClosedBeforePublishingCandidates() {
        var f = new MemoryRagTestSupport();
        for (int variant = 0; variant < 4; variant++) {
            int selected = variant;
            assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(RagException.class, () -> facade(f, (r, a, c, s) -> {
                if (selected == 0) return null;
                return new RagSearchResponse(1, selected == 1 ? "other-request" : r.requestId(), selected == 2 ? "memory.constraint" : r.spaceId(),
                        "rrf-v1", r.requestedMode(), r.requestedMode(), false, List.of(),
                        selected == 3 ? java.util.Collections.nCopies(51, absent("missing")) : List.of(), List.of());
            }).search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials)).code());
        }
        var calls = new AtomicInteger();
        assertEquals(RagErrorCode.DEPENDENCY_UNAVAILABLE, assertThrows(RagException.class, () -> facade(f, (r, a, c, s) -> {
            if (calls.incrementAndGet() == 1) return MemoryRagTestSupport.response(r, List.of(absent("a"), absent("b")));
            return new RagSearchResponse(1, "first-request-replayed", r.spaceId(), "rrf-v1", r.requestedMode(), r.requestedMode(), false,
                    List.of(), List.of(), List.of());
        }).search(poolRequest(2, 2, null, null), MemoryRagTestSupport.principal("ADMIN"), f.credentials)).code());
        assertEquals(2, calls.get());
    }

    private MemoryRagSearchFacade lockCallbackFacade(MemoryRagTestSupport f, MemoryRagSecurity security,
                                                    RagCandidate candidate, Runnable afterLock) {
        var jdbc = spy(f.jdbc);
        doAnswer(invocation -> {
            Integer lock = (Integer) invocation.callRealMethod();
            afterLock.run(); // Inside the real final-truth transaction, after the earlier identity check.
            return lock;
        }).when(jdbc).queryForObject("SELECT lock_id FROM memory_rag_lock WHERE lock_id=1 FOR UPDATE", Integer.class);
        return new MemoryRagSearchFacade((r, a, c, s) -> MemoryRagTestSupport.response(r, List.of(candidate)),
                security, jdbc, f.properties, f.projection);
    }

    @Test
    void tokenRevokedInsideFinalTruthTransactionCannotProduceRpcResponse() {
        var f = new MemoryRagTestSupport();
        var pref = MemoryRagTestSupport.preference("locked", ".9", observed(AS_OF)); f.upsert(pref);
        var candidate = f.candidate(pref, .8, RagScoreKind.SEMANTIC_NORMALIZED);
        var security = spy(f.security);
        var revoked = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            if (revoked.get()) throw new RagException(RagErrorCode.AUTHENTICATION_FAILED);
            return invocation.callRealMethod();
        }).when(security).authenticate(f.credentials);
        var search = lockCallbackFacade(f, security, candidate, () -> revoked.set(true));
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class, () ->
                search.search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials)).code());
        assertTrue(revoked.get());
        verify(security, times(2)).authenticate(f.credentials);
    }

    @Test
    void actorDriftInsideFinalTruthTransactionCannotProduceRpcResponse() {
        var f = new MemoryRagTestSupport();
        var pref = MemoryRagTestSupport.preference("locked", ".9", observed(AS_OF)); f.upsert(pref);
        var candidate = f.candidate(pref, .8, RagScoreKind.SEMANTIC_NORMALIZED);
        var security = spy(f.security);
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> changed.get()
                ? new AuthRagPrincipal(43L, "ADMIN", true, true, Long.MAX_VALUE) : invocation.callRealMethod())
                .when(security).authenticate(f.credentials);
        var search = lockCallbackFacade(f, security, candidate, () -> changed.set(true));
        assertEquals(RagErrorCode.AUTHENTICATION_FAILED, assertThrows(RagException.class, () ->
                search.search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials)).code());
        assertTrue(changed.get());
        verify(security, times(2)).authenticate(f.credentials);
    }

    @Test
    void validOwnerAfterFinalTruthTransactionKeepsCurrentSqlResult() {
        var f = new MemoryRagTestSupport();
        var pref = MemoryRagTestSupport.preference("locked", ".9", observed(AS_OF)); f.upsert(pref);
        var candidate = f.candidate(pref, .8, RagScoreKind.SEMANTIC_NORMALIZED);
        var security = spy(f.security);
        var locks = new AtomicInteger();
        var search = lockCallbackFacade(f, security, candidate, locks::incrementAndGet);
        var result = search.search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(1, locks.get());
        verify(security, times(2)).authenticate(f.credentials);
        assertEquals(1, result.matches().size());
        assertEquals("42", result.matches().get(0).preference().userId());
        assertEquals("value locked", result.matches().get(0).preference().preferenceValue());
        assertEquals(result.matches().get(0).candidate(), result.retrieval().candidates().get(0));
    }

    @Test
    void ownerFilterIsSignedAndSqlFinalLookupCannotReturnOtherUser() {
        var f = new MemoryRagTestSupport(); var pref = MemoryRagTestSupport.preference("key", ".9", observed(AS_OF)); f.upsert(pref);
        var other = new UserPreferenceRecord("43", "key", "other private value", "deployment", new BigDecimal(".9"), null, null, observed(AS_OF), 1);
        f.upsert(other);
        var result = facade(f, (r, a, c, scope) -> { assertEquals("42", scope.ownerId()); assertEquals("memory-service", scope.ownerDomain()); assertTrue(scope.activeOnly()); assertEquals(RagActorMode.USER, a.actorMode()); return MemoryRagTestSupport.response(r, List.of(f.candidate(other, .99, RagScoreKind.SEMANTIC_NORMALIZED), f.candidate(pref, .9, RagScoreKind.SEMANTIC_NORMALIZED))); }).search(request(1, null, null, List.of()), MemoryRagTestSupport.principal("ADMIN"), f.credentials);
        assertEquals(1, result.matches().size());
        assertEquals("42", result.matches().get(0).preference().userId());
        assertEquals(List.of(RagDocumentIds.preference("42", "key")), result.retrieval().candidates().stream().map(RagCandidate::documentId).toList());
        assertThrows(RagException.class, () -> facade(f, (r, a, c, s) -> { fail("must not retrieve"); return null; }).search(request(1, null, null, List.of(new RagFilter("ownerId", RagFilterOperator.EQ, RagValue.string("other")))), MemoryRagTestSupport.principal("ADMIN"), f.credentials));
    }
}
