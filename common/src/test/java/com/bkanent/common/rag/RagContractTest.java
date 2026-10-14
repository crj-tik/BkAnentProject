package com.bkanent.common.rag;

import com.bkanent.common.rpc.AuthPermissionRpcService;
import com.bkanent.common.rpc.AuthenticatedPrincipal;
import com.bkanent.common.rpc.RagPrincipalRpcService;
import com.bkanent.common.rpc.RagRetrievalRpcService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jacksonRecordsPreserveExplicitOptionsAndCanonicalHash() throws Exception {
        RagSearchRequest request = new RagSearchRequest(1, "r", "memory.preference", "部署",
                List.of(new RagFilter("category", RagFilterOperator.IN, RagValue.strings(List.of("deployment")))),
                null, null, null, null, null, null);
        RagSearchRequest restored = mapper.readValue(mapper.writeValueAsBytes(request), RagSearchRequest.class);
        assertThat(restored).isEqualTo(request);
        assertThat(RagCanonicalHash.requestHash(restored)).isEqualTo(RagCanonicalHash.requestHash(request.normalized()));
        assertThat(restored.normalized().recall()).isEqualTo(new RagRecallOptions(true, null, 100, 100));
        assertThat(restored.normalized().candidateLimit()).isEqualTo(50);
        assertThat(restored.normalized().rerankCandidates()).isEqualTo(100);
        assertThat(restored.normalized().failurePolicy()).isEqualTo(RagFailurePolicy.STRICT);
        assertThat(restored.requestedMode()).isEqualTo(RagSearchMode.HYBRID_RERANK);
    }

    @Test
    void singleRecallAndNoRerankAreExplicit() {
        RagSearchRequest request = request(new RagRecallOptions(false, RagRecallMode.VECTOR, null, 50), false, null, 50);
        assertThat(request.normalized().recall().keywordCandidates()).isNull();
        assertThat(request.requestedMode()).isEqualTo(RagSearchMode.VECTOR);
    }

    @Test
    void illegalCapacityAndDisabledStageOptionsNeverSilentlyChange() {
        invalid(() -> request(null, true, 100, 101).normalized());
        invalid(() -> request(new RagRecallOptions(false, null, null, 100), false, null, 50).normalized());
        invalid(() -> request(new RagRecallOptions(false, RagRecallMode.VECTOR, 0, 100), false, null, 50).normalized());
        invalid(() -> request(null, false, 100, 50).normalized());
        invalid(() -> request(new RagRecallOptions(true, null, 20, 20), true, 40, 50).normalized());
        invalid(() -> request(new RagRecallOptions(true, null, 20, 20), true, 50, 10).normalized());
        invalid(() -> request(new RagRecallOptions(true, null, 501, 20), true, 40, 10).normalized());
        invalid(() -> request(null, false, null, 201).normalized());
        invalid(() -> new RagFusionOptions(null, 0, 1.0, 1.0).normalized());
        invalid(() -> new RagFusionOptions(null, 60, Double.NaN, 1.0).normalized());
        invalid(() -> new RagFusionOptions(null, 60, Double.POSITIVE_INFINITY, 1.0).normalized());
    }

    @Test
    void v1BoundaryQuantitiesAndLegacyTwentyTwentyTenAreRepresentable() {
        RagSearchRequest legacy = request(new RagRecallOptions(true, null, 20, 20), true, 40, 10).normalized();
        assertThat(legacy.rerankCandidates()).isEqualTo(40);
        assertThat(legacy.candidateLimit()).isEqualTo(10);
        assertThat(request(new RagRecallOptions(true, null, 500, 500), true, 500, 200).normalized().candidateLimit()).isEqualTo(200);
        assertThat(request(new RagRecallOptions(false, RagRecallMode.KEYWORD, 1, null), false, null, 1).requestedMode())
                .isEqualTo(RagSearchMode.KEYWORD);
    }

    @Test
    void unknownFieldsStrategiesAndRawScriptsRejectByDefault() {
        assertThatThrownBy(() -> mapper.readValue("{\"contractVersion\":1,\"requestId\":\"r\",\"spaceId\":\"listing.knowledge\",\"query\":\"q\",\"nativeFilterExpression\":\"x\"}", RagSearchRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"strategy\":\"SUM\"}", RagFusionOptions.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"field\":\"status\",\"op\":\"SCRIPT\",\"value\":null}", RagFilter.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.InvalidFormatException.class);
        assertThat(RagSearchRequest.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("userId", "accessToken", "authentication", "ownerId", "collectionName", "indexName", "url");
    }

    @Test
    void valuesAreTypedCanonicalAndImmutable() {
        assertThat(RagValue.number(new BigDecimal("1.00"))).isEqualTo(RagValue.number(1));
        assertThat(RagValue.number(new BigDecimal("-0.000"))).isEqualTo(RagValue.number(0));
        assertThat(RagValue.instant(Instant.parse("2026-10-10T00:00:00Z")).scalar()).endsWith("Z");
        invalid(() -> RagValue.number(Double.NaN));
        invalid(() -> new RagValue(RagValueType.NUMBER, "1e999999999", null));
        invalid(() -> new RagValue(RagValueType.BOOLEAN, "yes", null));
        invalid(() -> new RagValue(RagValueType.INSTANT, "2026-10-10", null));
        invalid(() -> new RagFilter("category", RagFilterOperator.EQ, RagValue.strings(List.of("a"))));
        invalid(() -> new RagFilter("category", RagFilterOperator.GTE, RagValue.string("a")));
        invalid(() -> new RagFilter("category", RagFilterOperator.IN, RagValue.string("a")));
        invalid(() -> new RagFilter("region", RagFilterOperator.CONTAINS_CI, RagValue.number(3)));
        ArrayList<String> input = new ArrayList<>(List.of("a"));
        RagValue value = RagValue.strings(input);
        input.add("b");
        assertThat(value.values()).containsExactly("a");
        assertThatThrownBy(() -> value.values().add("b")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mapOrderDoesNotChangeHashesButTypesAndContentsDo() {
        Map<String, RagValue> first = new LinkedHashMap<>();
        first.put("category", RagValue.string("deployment"));
        first.put("confidence", RagValue.number(new BigDecimal("0.80")));
        Map<String, RagValue> second = new LinkedHashMap<>();
        second.put("confidence", RagValue.number(new BigDecimal("0.8")));
        second.put("category", RagValue.string("deployment"));
        RagDocument a = new RagDocument("preference", "p", "body", first);
        RagDocument b = new RagDocument("preference", "p", "body", second);
        assertThat(RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, a))
                .isEqualTo(RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, b));
        first.put("confidence", RagValue.string("0.8"));
        assertThat(RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, new RagDocument("preference", "p", "body", first)))
                .isNotEqualTo(RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, b));
        assertThat(a.attributes().get("confidence").type()).isEqualTo(RagValueType.NUMBER);
        assertThatThrownBy(() -> a.attributes().put("x", RagValue.bool(true))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void eventPayloadIdentityExcludesTransportButIncludesRevisionSchemaAndOperation() throws Exception {
        RagDocument doc = new RagDocument("listing", "1", "body", Map.of("active", RagValue.bool(true)));
        String hash = RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, doc);
        RagIndexEvent first = event("r1", "e1", 7, "v1", RagIndexOperation.UPSERT, hash, doc, Instant.ofEpochSecond(10));
        RagIndexEvent retry = event("r2", "e2", 7, "v1", RagIndexOperation.UPSERT, hash, doc, Instant.ofEpochSecond(20));
        assertThat(RagCanonicalHash.eventPayloadHash(first)).isEqualTo(RagCanonicalHash.eventPayloadHash(retry));
        assertThat(RagCanonicalHash.requestHash(first)).isNotEqualTo(RagCanonicalHash.requestHash(retry));
        assertThat(RagCanonicalHash.eventPayloadHash(event("r1", "e1", 8, "v1", RagIndexOperation.UPSERT, hash, doc, first.createdAt())))
                .isNotEqualTo(RagCanonicalHash.eventPayloadHash(first));
        assertThat(RagCanonicalHash.eventPayloadHash(event("r1", "e1", 7, "v2", RagIndexOperation.UPSERT, hash, doc, first.createdAt())))
                .isNotEqualTo(RagCanonicalHash.eventPayloadHash(first));
        RagIndexEvent delete = event("r", "e", 8, "v1", RagIndexOperation.DELETE,
                RagCanonicalHash.documentHash(RagIndexOperation.DELETE, null), null, first.createdAt());
        assertThat(delete.document()).isNull();
        invalid(() -> event("r", "e", 8, "v1", RagIndexOperation.DELETE, hash, doc, first.createdAt()));
        invalid(() -> event("r", "e", 8, "v1", RagIndexOperation.UPSERT, hash, null, first.createdAt()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(first); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThat(input.readObject()).isEqualTo(first);
        }
    }

    @Test
    void canonicalEncodingNeverCollidesOnSegmentBoundariesOrMalformedUnicode() {
        assertThat(RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, new RagDocument("ab", "c", "body", null)))
                .isNotEqualTo(RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, new RagDocument("a", "bc", "body", null)));
        invalid(() -> RagCanonicalHash.requestHash(RagSearchRequest.defaults("r", "listing.knowledge", "\uD800")));
        invalid(() -> RagDocumentIds.constraint("\uD800"));
        assertThat(RagDocumentIds.preference("a_b", "c")).isNotEqualTo(RagDocumentIds.preference("a", "b_c"));
        assertThat(RagDocumentIds.preference("a:b", "c")).isNotEqualTo(RagDocumentIds.preference("a", "b:c"));
        assertThat(RagDocumentIds.listing(1)).isEqualTo("listing:1");
        invalid(() -> RagDocumentIds.preference("1", "房".repeat(100)));
    }

    @Test
    void selectorsAndUnknownLegacyRevisionsFailClosed() {
        invalid(() -> new RagStatusRequest(1, "r", "s", null, null));
        invalid(() -> new RagReplayRequest(1, "r", "s", "e", "d"));
        invalid(() -> new RagProvisionRequest(1, "r", "s", "v1", 0));
        assertThat(RagCanonicalHash.requestHash(new RagListingIndexRequest(1, "r", 1L))).hasSize(64);
        RagListingSearchRequest listing = new RagListingSearchRequest(1, "r", "q", null, null, null, null,
                null, null, 20, 20, 10);
        assertThat(RagCanonicalHash.requestHash(listing)).hasSize(64);
        MemoryRagSearchRequest memory = new MemoryRagSearchRequest(RagSearchRequest.defaults("r", "memory.preference", "q"), 5, 0.5, 30.0);
        assertThat(RagCanonicalHash.requestHash(memory)).hasSize(64);
    }

    @Test
    void principalResponseKeepsOriginalInterfaceCompatibleAndHashesOnlyFacts() throws Exception {
        assertThat(AuthPermissionRpcService.class.getMethod("resolvePrincipal", String.class).getReturnType()).isEqualTo(AuthenticatedPrincipal.class);
        assertThat(RagPrincipalRpcService.class.getMethod("resolveRagPrincipal", RagCredentialContext.class, RagAuthEnvelope.class).getReturnType())
                .isEqualTo(AuthRagPrincipal.class);
        assertThat(RagRetrievalRpcService.class.getMethod("search", RagSearchRequest.class, RagAuthEnvelope.class,
                RagCredentialContext.class, RagAuthorizationScope.class).getParameterCount()).isEqualTo(4);
        AuthRagPrincipal principal = new AuthRagPrincipal(1L, "ADMIN", true, true, 500);
        assertThat(mapper.readValue(mapper.writeValueAsBytes(principal), AuthRagPrincipal.class)).isEqualTo(principal);
        AuthRagPrincipal signed = new AuthRagPrincipal(1L, "ADMIN", true, true, 500,
                new RagAuthEnvelope(1, "key", "rag-service", "auth-service", RagOperation.RESOLVE_PRINCIPAL_RESPONSE,
                        "r", "hash", RagActorMode.USER, "1", 1, 2, "digest", "signature"));
        assertThat(RagCanonicalHash.principalHash(principal)).isEqualTo(RagCanonicalHash.principalHash(signed));
        assertThat(RagCanonicalHash.principalHash(principal)).isNotEqualTo(RagCanonicalHash.principalHash(new AuthRagPrincipal(1L, "ADMIN", true, false, 500)));
    }

    @Test
    void listingAndMemoryResponseGraphsSerializeWithoutServiceJarOrAdapters() throws Exception {
        RagCandidate candidate = new RagCandidate("listing:1", "listing", "1", "body", 1, "v1",
                List.of(RagRecallMode.KEYWORD, RagRecallMode.VECTOR), 4.0, 0.8, 0.03, 2.0, 0.88,
                RagScoreKind.SEMANTIC_NORMALIZED, "score-v1", Map.of("title", RagValue.string("title")));
        RagSearchResponse retrieval = new RagSearchResponse(1, "r", "listing.knowledge", "rrf-v1",
                RagSearchMode.HYBRID_RERANK, RagSearchMode.HYBRID_RERANK, false, List.of(), List.of(candidate), List.of());
        com.bkanent.common.model.ListingDTO detail = new com.bkanent.common.model.ListingDTO(1L, "title", "address",
                "layout", new BigDecimal("80"), new BigDecimal("200"), "ACTIVE", "LOW", "NONE", "school", "bus", "VERIFIED");
        RagListingSearchResponse listing = new RagListingSearchResponse(1, "r", List.of(new RagListingMatch(detail,
                4.0, 0.8, 2.0, 0.88, candidate.sources(), candidate)), "context", retrieval);
        assertThat(javaRoundTrip(listing)).isEqualTo(listing);
        assertThat(mapper.readValue(mapper.writeValueAsBytes(listing), RagListingSearchResponse.class)).isEqualTo(listing);
        com.bkanent.common.agent.UserPreferenceRecord preference = new com.bkanent.common.agent.UserPreferenceRecord(
                "1", "key", "value", "deployment", new BigDecimal("0.8"), "evidence", "session",
                java.time.LocalDateTime.of(2026, 10, 10, 0, 0), 3);
        com.bkanent.common.agent.SystemConstraintRecord constraint = new com.bkanent.common.agent.SystemConstraintRecord(
                "key", "text", "category", "source", "tags", true);
        MemoryRagSearchResponse memory = new MemoryRagSearchResponse(retrieval, List.of(
                new MemoryRagMatch(candidate, preference, null, 0.8),
                new MemoryRagMatch(candidate, null, constraint, 0.7)), "domain-v1");
        assertThat(javaRoundTrip(memory)).isEqualTo(memory);
        // common deliberately has no JavaTimeModule dependency; production service mappers register it.
        // Exercise JSON record shape with null time, while the native roundtrip above covers actual UTC facts.
        com.bkanent.common.agent.UserPreferenceRecord jsonPreference = new com.bkanent.common.agent.UserPreferenceRecord(
                "1", "key", "value", "deployment", new BigDecimal("0.8"), "evidence", "session", null, 3);
        MemoryRagSearchResponse jsonMemory = new MemoryRagSearchResponse(retrieval, List.of(
                new MemoryRagMatch(candidate, jsonPreference, null, 0.8),
                new MemoryRagMatch(candidate, null, constraint, 0.7)), "domain-v1");
        assertThat(mapper.readValue(mapper.writeValueAsBytes(jsonMemory), MemoryRagSearchResponse.class)).isEqualTo(jsonMemory);
    }

    private static Object javaRoundTrip(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(value); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return input.readObject();
        }
    }

    private static RagSearchRequest request(RagRecallOptions recall, Boolean rerank, Integer pool, Integer limit) {
        return new RagSearchRequest(1, "r", "listing.knowledge", "q", null, recall, null, rerank, pool, limit, null);
    }

    private static RagIndexEvent event(String requestId, String eventId, long revision, String schema,
                                       RagIndexOperation operation, String hash, RagDocument doc, Instant at) {
        return new RagIndexEvent(1, requestId, eventId, "listing.knowledge", "listing:1", revision, schema, operation, hash, doc, at);
    }

    private static void invalid(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(RagException.class).extracting("code").isEqualTo(RagErrorCode.INVALID_ARGUMENT);
    }
}
