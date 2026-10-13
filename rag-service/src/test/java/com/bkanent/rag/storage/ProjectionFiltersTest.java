package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import com.bkanent.rag.index.IndexDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.milvus.MilvusFilterExpressionConverter;
import java.time.Instant;
import java.util.*;
import static com.bkanent.rag.retrieval.RetrievalTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectionFiltersTest {
    @Test void caseInsensitiveContainsIsLiteralAndWorksForNonAscii() {
        var filters = List.of(new RagFilter("region", RagFilterOperator.CONTAINS_CI, RagValue.string("北京 A*\\\"")));
        ProjectionFilters.validate(space(), filters);
        assertTrue(ProjectionFilters.matches(Map.of("region", RagValue.string("地址北京 a*\\\"小区")), filters));
        assertFalse(ProjectionFilters.matches(Map.of(), filters));
        assertNull(ProjectionFilters.vector(filters));
    }
    @Test void typedMandatoryScopeCompilesToAstAndCannotUseUnknownFieldOrInvalidRange() {
        var filters = List.of(new RagFilter("ownerId", RagFilterOperator.EQ, RagValue.string("user\"a")),
                new RagFilter("resourceId", RagFilterOperator.IN, RagValue.strings(List.of("1", "2"))));
        ProjectionFilters.validate(space(), filters);
        assertNotNull(ProjectionFilters.vector(filters));
        assertThrows(RagException.class, () -> ProjectionFilters.validate(space(),
                List.of(new RagFilter("ownerId", RagFilterOperator.GTE, RagValue.string("a")))));
        assertThrows(RagException.class, () -> ProjectionFilters.validate(space(),
                List.of(new RagFilter("ownerId\"] || true", RagFilterOperator.EQ, RagValue.string("a")))));
    }
    @Test void physicalIdsAreStableSafeBoundedWhileLogicalIdentityIsUnchanged() {
        String logical = "preference:" + "长_'\\身份".repeat(50);
        String id = ProjectionCodec.physicalId(logical);
        assertEquals(36, id.length());
        assertEquals(id, ProjectionCodec.physicalId(logical));
        assertNotEquals(id, ProjectionCodec.physicalId(logical + "x"));
        assertDoesNotThrow(() -> UUID.fromString(id));
    }
    @Test void numericBoundariesRejectNonfiniteUnderflowAndPrecisionLoss() {
        assertThrows(RagException.class, () -> ProjectionFilters.raw(RagValue.number(new java.math.BigDecimal("1e1000"))));
        assertThrows(RagException.class, () -> ProjectionFilters.raw(RagValue.number(new java.math.BigDecimal("1e-1000"))));
        assertThrows(RagException.class, () -> ProjectionFilters.raw(RagValue.number(new java.math.BigDecimal("9007199254740993"))));
        assertThrows(RagException.class, () -> ProjectionFilters.raw(RagValue.numbers(List.of(new java.math.BigDecimal("1e1000")))));
        assertEquals(.1, ProjectionFilters.raw(RagValue.number(new java.math.BigDecimal("0.1"))));
    }
    @Test void instantStorageAndFilterLiteralsHaveFixedWidthNanosecondsAndSameChronologicalOrder() {
        String zero = (String) ProjectionFilters.raw(RagValue.instant(java.time.Instant.parse("2026-10-12T00:00:00Z")));
        String fraction = (String) ProjectionFilters.raw(RagValue.instant(java.time.Instant.parse("2026-10-12T00:00:00.1Z")));
        assertEquals("2026-10-12T00:00:00.000000000Z", zero);
        assertEquals("2026-10-12T00:00:00.100000000Z", fraction);
        assertTrue(zero.compareTo(fraction) < 0);
        assertThrows(RagException.class, () -> ProjectionFilters.raw(RagValue.instant(java.time.Instant.parse("1900-01-01T00:00:00Z"))));
        assertThrows(RagException.class, () -> ProjectionFilters.raw(RagValue.instant(java.time.Instant.parse("+10000-01-01T00:00:00Z"))));
    }
    @Test void memoryCategoryAndTimeRangeAreInclusiveAndCompileWithOwnerAsAnAnd() {
        Instant lower = Instant.parse("2026-10-10T00:00:00.000000001Z");
        Instant upper = Instant.parse("2026-10-12T00:00:00.999999999Z");
        var filters = List.of(new RagFilter("userId", RagFilterOperator.EQ, RagValue.string("7")),
                new RagFilter("category", RagFilterOperator.IN, RagValue.strings(List.of("deployment"))),
                new RagFilter("lastObservedAt", RagFilterOperator.GTE, RagValue.instant(lower)),
                new RagFilter("lastObservedAt", RagFilterOperator.LTE, RagValue.instant(upper)));
        ProjectionFilters.validate(memorySpace(), filters);
        Map<String, RagValue> attributes = new HashMap<>(Map.of("userId", RagValue.string("7"),
                "category", RagValue.string("deployment")));
        for (Instant included : List.of(lower, lower.plusNanos(1), upper.minusNanos(1), upper)) {
            attributes.put("lastObservedAt", RagValue.instant(included));
            assertTrue(ProjectionFilters.matches(attributes, filters), included.toString());
        }
        for (Instant excluded : List.of(lower.minusNanos(1), upper.plusNanos(1))) {
            attributes.put("lastObservedAt", RagValue.instant(excluded));
            assertFalse(ProjectionFilters.matches(attributes, filters), excluded.toString());
        }
        attributes.remove("lastObservedAt");
        assertFalse(ProjectionFilters.matches(attributes, filters));
        attributes.put("lastObservedAt", RagValue.instant(lower));
        attributes.put("category", RagValue.string("communication"));
        assertFalse(ProjectionFilters.matches(attributes, filters));
        attributes.put("category", RagValue.string("deployment"));
        attributes.put("userId", RagValue.string("8"));
        assertFalse(ProjectionFilters.matches(attributes, filters));
        attributes.remove("userId");
        assertFalse(ProjectionFilters.matches(attributes, filters));
        String expression = new MilvusFilterExpressionConverter().convertExpression(ProjectionFilters.vector(filters));
        assertTrue(expression.contains("metadata[\"userId\"] == \"7\""), expression);
        assertTrue(expression.contains("metadata[\"category\"] in [\"deployment\"]"), expression);
        assertTrue(expression.contains("metadata[\"lastObservedAt\"] >= \"2026-10-10T00:00:00.000000001Z\""), expression);
        assertTrue(expression.contains("metadata[\"lastObservedAt\"] <= \"2026-10-12T00:00:00.999999999Z\""), expression);
        assertEquals(3, expression.split(" && ", -1).length - 1, expression);
    }
    @Test void memoryTypedProjectionRoundTripsForBothRoutesAndOnlyDeclaredFieldsArePublic() throws Exception {
        var memory = memorySpace();
        Instant observed = Instant.parse("2026-10-10T00:00:00.123456789Z");
        Map<String, RagValue> attributes = Map.of("userId", RagValue.string("7"), "ownerId", RagValue.string("7"),
                "category", RagValue.string("deployment"), "preferenceKey", RagValue.string("deployment.mode"),
                "confidence", RagValue.number(.75), "lastObservedAt", RagValue.instant(observed),
                "observationCount", RagValue.number(3), "active", RagValue.bool(true));
        var document = new RagDocument("user_preference", "7:deployment.mode", "用户偏好本地部署", attributes);
        String hash = RagCanonicalHash.documentHash(RagIndexOperation.UPSERT, document);
        var event = new RagIndexEvent(1, "request", "memory-event", memory.spaceId(), "preference:7:deployment.mode",
                3, memory.schemaVersion(), RagIndexOperation.UPSERT, hash, document, observed);
        Map<String, Object> metadata = ProjectionCodec.metadata(new IndexDocument(event, "payload"), memory);
        assertEquals("2026-10-10T00:00:00.123456789Z", metadata.get("lastObservedAt"));
        assertEquals(.75, metadata.get("confidence"));
        assertEquals(3.0, metadata.get("observationCount"));
        assertEquals(false, metadata.get("tombstone"));
        var mapper = new ObjectMapper();
        Map<String, Object> jsonMetadata = mapper.readValue(mapper.writeValueAsString(metadata), new TypeReference<>() { });
        Map<String, RagValue> expectedPublic = new HashMap<>(attributes);
        expectedPublic.keySet().retainAll(memory.returnFields());
        for (Map<String, Object> encoded : List.of(metadata, jsonMetadata)) {
            for (RagRecallMode mode : RagRecallMode.values()) {
                var decoded = ProjectionCodec.hit(ProjectionCodec.physicalId(event.documentId()), document.content(), encoded, .8, mode, memory);
                var candidate = decoded.candidate();
                Map<String, RagValue> expectedInternal = new HashMap<>(attributes);
                expectedInternal.put("contentHash", RagValue.string(hash));
                assertEquals(expectedInternal, candidate.metadata());
                assertEquals(hash, decoded.contentHash());
                assertEquals(event.documentId(), candidate.documentId());
                assertEquals(document.sourceType(), candidate.sourceType());
                assertEquals(document.sourceId(), candidate.sourceId());
                assertEquals(event.revision(), candidate.projectionVersion());
                assertEquals(memory.schemaVersion(), candidate.schemaVersion());
                Map<String, RagValue> internal = new HashMap<>(candidate.metadata());
                internal.put("internalNote", RagValue.string("not public"));
                var withInternal = new RagCandidate(candidate.documentId(), candidate.sourceType(), candidate.sourceId(),
                        candidate.content(), candidate.projectionVersion(), candidate.schemaVersion(), candidate.sources(),
                        candidate.keywordScore(), candidate.vectorScore(), candidate.fusionScore(), candidate.rawRerankScore(),
                        candidate.relevanceScore(), candidate.scoreKind(), candidate.scoringVersion(), internal);
                var published = ProjectionCodec.publicCandidate(withInternal, memory);
                assertEquals(expectedPublic, published.metadata());
                assertTrue(Collections.disjoint(published.metadata().keySet(), Set.of("ownerId", "userId", "contentHash", "internalNote")));
                assertEquals(document.content(), published.content());
                assertEquals(candidate.documentId(), published.documentId());
                assertEquals(List.of(mode), published.sources());
                assertEquals(mode == RagRecallMode.KEYWORD ? .8 : null, published.keywordScore());
                assertEquals(mode == RagRecallMode.VECTOR ? .8 : null, published.vectorScore());
                assertEquals(internal, withInternal.metadata());
            }
        }
    }
    private static RagSpaceDefinition memorySpace() {
        var base = space();
        Map<String, RagSpaceDefinition.FilterField> fields = new HashMap<>(base.fields());
        fields.put("userId", new RagSpaceDefinition.FilterField(RagValueType.STRING, Set.of(RagFilterOperator.EQ), true));
        for (String name : List.of("category", "preferenceKey")) {
            fields.put(name, new RagSpaceDefinition.FilterField(RagValueType.STRING,
                    Set.of(RagFilterOperator.EQ, RagFilterOperator.IN), true));
        }
        fields.put("lastObservedAt", new RagSpaceDefinition.FilterField(RagValueType.INSTANT,
                Set.of(RagFilterOperator.GTE, RagFilterOperator.LTE), true));
        for (String name : List.of("confidence", "observationCount")) {
            fields.put(name, new RagSpaceDefinition.FilterField(RagValueType.NUMBER,
                    Set.of(RagFilterOperator.GTE, RagFilterOperator.LTE), true));
        }
        return new RagSpaceDefinition("memory.preference", "memory", base.policyVersion(), base.schemaVersion(),
                "rag_memory_preference_v1", base.vectorSchema(), fields,
                Set.of("category", "preferenceKey", "confidence", "lastObservedAt", "observationCount"),
                base.searchRoles(), base.adminRoles(), base.allowedClients(), base.limits(), base.rerankProfile());
    }
    @Test void documentValidationRejectsUnknownTypesAndBudgets() {
        assertThrows(RagException.class, () -> ProjectionFilters.validateDocument(space(),
                new RagDocument("listing", "1", "body", Map.of("area", RagValue.string("1")))));
        assertThrows(RagException.class, () -> ProjectionFilters.validateDocument(space(),
                new RagDocument("listing", "1", "body", Map.of("ownerName", RagValue.string("secret")))));
        assertThrows(RagException.class, () -> ProjectionFilters.validateDocument(space(),
                new RagDocument("listing", "1", "长".repeat(3000), Map.of())));
    }
}
