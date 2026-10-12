package com.bkanent.rag.storage;

import com.bkanent.common.rag.*;
import org.junit.jupiter.api.Test;
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
    @Test void documentValidationRejectsUnknownTypesAndBudgets() {
        assertThrows(RagException.class, () -> ProjectionFilters.validateDocument(space(),
                new RagDocument("listing", "1", "body", Map.of("area", RagValue.string("1")))));
        assertThrows(RagException.class, () -> ProjectionFilters.validateDocument(space(),
                new RagDocument("listing", "1", "body", Map.of("ownerName", RagValue.string("secret")))));
        assertThrows(RagException.class, () -> ProjectionFilters.validateDocument(space(),
                new RagDocument("listing", "1", "长".repeat(3000), Map.of())));
    }
}
