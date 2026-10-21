package com.bkanent.agent.memory;

import com.bkanent.common.memory.*;
import com.bkanent.agent.orchestration.StoredModelMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SupervisorMemoryReferentResolverTest {
    final MemoryContextKey context = new MemoryContextKey("1", "supervisor", "supervisor-agent", "session");
    final SupervisorMemoryReferentResolver resolver = new SupervisorMemoryReferentResolver();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    MemoryEntityAnchor entity(String id, String matter, String alias) {
        var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, context, "message-1", "event-1", 1L, new MemoryRevision(1, "a".repeat(64), "message.v2"));
        var e = new MemoryEntityAnchor(id, context, matter, new MemoryRevision(1, "0".repeat(64), "entity.v2.1"), List.of(alias), "有来源的简要说明", List.of(new MemorySourceDependency(source, null)));
        return new MemoryEntityAnchor(id, context, matter, new MemoryRevision(1, MemoryCanonicalHash.entityHash(e), "entity.v2.1"), e.aliases(), e.briefDefinition(), e.dependencies());
    }
    MemoryWorkingSetResponse snapshot(List<MemoryEntityAnchor> entities) {
        var dependencies = entities.stream().flatMap(e -> e.dependencies().stream()).distinct().toList();
        var summary = entities.isEmpty() ? List.<MemorySummary>of() : List.of(new MemorySummary("summary", context, new MemoryAssociation(null, null, null, null), new MemoryRevision(1, "a".repeat(64), "summary.v2.1"), 1, null,
                new MemoryCoveredRange(1, 1), List.of(), List.of(new MemorySummary.Item(MemorySummary.ItemKind.FACT, "已知实体", dependencies)), entities, dependencies));
        return new MemoryWorkingSetResponse(context, new MemoryConversationVersion(1, 1, "policy"), List.of(), summary, List.of());
    }
    @Test void localExactAliasUsesNoRemoteCallAndReturnsBriefAndEvidence() {
        var e = entity("entity-1", "matter-1", "那套房");
        var result = resolver.resolve(snapshot(List.of(e)), new SupervisorMemoryReferentResolver.Request("那套房", null), cursor -> { throw new AssertionError("no remote lookup"); });
        assertEquals("RESOLVED", result.status()); assertEquals(List.of(e), result.candidates()); assertFalse(result.remoteLookup()); assertEquals(e.dependencies(), result.dependencies());
    }
    @Test void duplicateAliasesNeedClarificationAndMatterCanNarrowIdentity() {
        var a = entity("one", "m1", "小区A"); var b = entity("two", "m2", "小区A"); var snapshot = snapshot(List.of(a,b));
        assertEquals("NEEDS_CLARIFICATION", resolver.resolve(snapshot, new SupervisorMemoryReferentResolver.Request("小区A", null), c -> { throw new AssertionError(); }).status());
        assertEquals(List.of(b), resolver.resolve(snapshot, new SupervisorMemoryReferentResolver.Request("小区A", "m2"), c -> { throw new AssertionError(); }).candidates());
    }
    @Test void gapUsesTwoBoundedPagesAndNeverClaimsUniqueFromIncompleteScan() {
        AtomicInteger calls = new AtomicInteger(); var e = entity("one", "m1", "小区A");
        var result = resolver.resolve(snapshot(List.of()), new SupervisorMemoryReferentResolver.Request("小区A", null), c -> new MemoryEntityQueryResponse(List.of(e), "cursor-" + calls.incrementAndGet(), true));
        assertEquals(2, calls.get()); assertEquals("INCOMPLETE_NEEDS_CLARIFICATION", result.status()); assertEquals(List.of(e), result.candidates());
    }
    @Test void missingAndSimilarNamesAreNotMergedAndEmptyPageStops() {
        AtomicInteger calls = new AtomicInteger();
        var result = resolver.resolve(snapshot(List.of(entity("one", "m1", "小区A"))), new SupervisorMemoryReferentResolver.Request("小区a", null), c -> { calls.incrementAndGet(); return new MemoryEntityQueryResponse(List.of(), null, false); });
        assertEquals("NEEDS_CLARIFICATION", result.status()); assertTrue(result.candidates().isEmpty()); assertEquals(1, calls.get());
    }
    @Test void schemaCannotWidenOwnerDomainAndInvalidRemoteIdentityIsRejected() {
        assertThrows(MemoryException.class, () -> resolver.parse(mapper, "{\"reference\":\"房\",\"ownerId\":\"2\"}"));
        assertThrows(MemoryException.class, () -> resolver.parse(mapper, "{\"reference\":12}"));
        assertThrows(MemoryException.class, () -> resolver.parse(mapper, "{\"reference\":\"房\",\"matterId\":null}"));
        var e = entity("one", "m1", "房");
        var tampered = new MemoryEntityAnchor(e.entityId(), e.contextKey(), e.matterId(), e.version(), e.aliases(), "改写", e.dependencies());
        assertThrows(MemoryException.class, () -> resolver.resolve(snapshot(List.of()), new SupervisorMemoryReferentResolver.Request("房", null), c -> new MemoryEntityQueryResponse(List.of(tampered), null, false)));
    }
    @Test void oldJournalJsonRemainsIdenticalAndNewAssociationCannotRetagEarlierMessages() throws Exception {
        String old = "{\"role\":\"user\",\"text\":\"原文\",\"calls\":[],\"responses\":[],\"memoryDependencies\":[]}";
        assertEquals(old, mapper.writeValueAsString(mapper.readValue(old, StoredModelMessage.class)));
        var start = StoredModelMessage.user("原文", "previous", new MemoryMatterIntent(MemoryMatterIntent.Action.START, null, 0, "买房"));
        assertNull(start.matterId()); assertEquals("原文", start.text());
        var close = StoredModelMessage.user("原文", "previous", new MemoryMatterIntent(MemoryMatterIntent.Action.CLOSE, "target", 1, null));
        assertEquals("target", close.matterId()); assertNull(start.matterId());
    }
}
