package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rag.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryV2RagProjectionServiceTest {
    @Test
    void preferenceFragmentsShareRevisionAndRemovedFragmentsStayTombstoned() {
        var fixture = new MemoryRagTestSupport();
        fixture.properties.setDomainProjectionEnabled(true);
        var projections = new MemoryV2RagProjectionService(fixture.projection, fixture.properties, fixture.jdbc);
        var context = new MemoryContextKey("42", "marketing", "marketing-agent", "conversation-1");
        var scope = MemoryScope.privateScope(context.domainId(), context.agentId());
        var key = new PreferenceBlockKey(context.ownerId(), context.ownerId(), scope, "budget");
        String blockId = "p-" + key.identityHash();
        var sourceVersion = new MemoryRevision(1, "c".repeat(64), "memory-v2.1");
        var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, context, "message-1", "event-1", 1L, sourceVersion);
        var dependency = new MemorySourceDependency(source, null);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        List<PreferenceTransition> longHistory = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            longHistory.add(transition("transition-" + i, i, i, "预算".repeat(140), dependency, now));
        }
        var original = block(blockId, key, 100, "a".repeat(64), "transition-100", dependency, now);
        String space = MemoryRagSpaceIds.spaceId(MemoryRagRecordType.PREFERENCE_HISTORY,
                context.domainId(), context.agentId());
        fixture.tx.executeWithoutResult(status -> projections.preference(original, longHistory));

        List<String> originalIds = fixture.jdbc.queryForList(
                "SELECT document_id FROM memory_rag_projection WHERE space_id=? ORDER BY document_id", String.class, space);
        assertTrue(originalIds.size() > 1, "long logical history should use multiple bounded fragments");
        for (String id : originalIds) {
            RagIndexEvent event = fixture.latest(id);
            assertEquals(RagIndexOperation.UPSERT, event.operation());
            assertFalse(event.document().content().contains(context.conversationId()));
            assertFalse(event.document().content().contains("event-1"));
            assertEquals("100", event.document().attributes().get("sourceVersion").scalar());
            assertEquals(Integer.toString(originalIds.size()), event.document().attributes().get("fragmentCount").scalar());
        }

        var shortenedTransition = transition("transition-short", 1, 101, "当前预算", dependency, now.plusSeconds(1));
        var shortened = block(blockId, key, 101, "b".repeat(64), "transition-short", dependency, now.plusSeconds(1));
        fixture.tx.executeWithoutResult(status -> projections.preference(shortened, List.of(shortenedTransition)));

        RagIndexEvent current = fixture.latest(originalIds.get(0));
        assertEquals(RagIndexOperation.UPSERT, current.operation());
        assertEquals("101", current.document().attributes().get("sourceVersion").scalar());
        assertEquals("1", current.document().attributes().get("fragmentCount").scalar());
        for (String staleId : originalIds.subList(1, originalIds.size())) {
            assertEquals(RagIndexOperation.DELETE, fixture.latest(staleId).operation());
            assertTrue(fixture.latest(staleId).document() == null);
        }

        int outboxBeforeRepeat = fixture.count("memory_rag_outbox");
        fixture.tx.executeWithoutResult(status -> projections.preference(shortened, List.of(shortenedTransition)));
        assertEquals(outboxBeforeRepeat + 1, fixture.count("memory_rag_outbox"),
                "an already tombstoned fragment must not be republished on every update");
    }

    private static PreferenceTransition transition(String id, long ordinal, long revision, String value,
            MemorySourceDependency dependency, Instant at) {
        return new PreferenceTransition(id, ordinal, revision, PreferenceTransition.Assessment.PREFERENCE,
                PreferenceTransition.Change.OBSERVATION, PreferenceTransition.Polarity.AFFIRMATIVE,
                MemoryJsonValue.string(value), null, null, at, at, at, null, List.of(), .8,
                "extract-v1", List.of(dependency));
    }

    private static PreferenceBlock block(String blockId, PreferenceBlockKey key, long revision, String contentHash,
            String currentTransition, MemorySourceDependency dependency, Instant observedAt) {
        var current = new PreferenceCurrentState(PreferenceCurrentState.Status.EFFECTIVE,
                MemoryJsonValue.string("current value"), List.of(currentTransition), List.of(), List.of(),
                .8, .9, observedAt, List.of(dependency));
        var version = new MemoryRevision(revision, contentHash, "memory-v2.1");
        return new PreferenceBlock(blockId, key, version, MemoryState.ACTIVE, current,
                new PreferenceHistoryCursor(blockId, revision, 1, 0));
    }
}
