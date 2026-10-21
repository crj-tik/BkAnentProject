package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import com.bkanent.common.rag.*;
import com.bkanent.memory.config.MemoryRagProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Writes bounded, versioned private memory projections in the owning SQL transaction. */
@Service
public class MemoryV2RagProjectionService {
    public static final String SCHEMA = "memory-v2.1";
    private static final int MAX_FRAGMENT_BYTES = 20 * 1024;
    private static final int MAX_FRAGMENTS = 256;
    private final MemoryRagProjectionService projections;
    private final MemoryRagProperties properties;
    private final JdbcTemplate jdbc;

    public MemoryV2RagProjectionService(MemoryRagProjectionService projections, MemoryRagProperties properties,
            JdbcTemplate jdbc) {
        this.projections = projections;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    public boolean enabled() { return properties.isDomainProjectionEnabled(); }

    public void preference(PreferenceBlock block, List<PreferenceTransition> history) {
        if (!enabled()) return;
        MemoryContextKey context = block.key().scope().kind() == MemoryScope.Kind.DOMAIN_PRIVATE
                ? new MemoryContextKey(block.key().ownerId(), block.key().scope().domainId(), block.key().scope().agentId(), "preference-history")
                : null;
        if (context == null || block.state() != MemoryState.ACTIVE) {
            if (context != null) removePreference(context, block.blockId());
            return;
        }
        String space = MemoryRagSpaceIds.spaceId(MemoryRagRecordType.PREFERENCE_HISTORY,
                context.domainId(), context.agentId());
        String prefix = "mph-" + hash(List.of(context.ownerId(), context.domainId(), context.agentId(), block.blockId())) + "-";
        List<String> fragments = fragments(block, history);
        if (fragments.size() > MAX_FRAGMENTS) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        Set<String> liveIds = new HashSet<>();
        for (int i = 0; i < fragments.size(); i++) {
            String documentId = prefix + String.format(Locale.ROOT, "%03d", i);
            liveIds.add(documentId);
            Map<String, RagValue> attrs = privateAttributes(context, block.version().revision(), block.version().contentHash());
            attrs.put("blockId", RagValue.string(block.blockId()));
            attrs.put("dimensionId", RagValue.string(block.key().dimensionId()));
            attrs.put("fragmentIndex", RagValue.number(i));
            attrs.put("fragmentCount", RagValue.number(fragments.size()));
            RagDocument document = new RagDocument("memory_preference_history", block.blockId(), fragments.get(i), attrs);
            projections.publish(space, documentId, SCHEMA, document);
        }
        removeStale(space, prefix, liveIds);
    }

    public void removePreference(MemoryContextKey context, String blockId) {
        if (!enabled()) return;
        String space = MemoryRagSpaceIds.spaceId(MemoryRagRecordType.PREFERENCE_HISTORY, context.domainId(), context.agentId());
        String prefix = "mph-" + hash(List.of(context.ownerId(), context.domainId(), context.agentId(), blockId)) + "-";
        removeStale(space, prefix, Set.of());
    }

    public void experience(MemoryExperience experience) {
        if (!enabled()) return;
        MemoryContextKey context = experience.contextKey();
        String space = MemoryRagSpaceIds.spaceId(MemoryRagRecordType.EXPERIENCE, context.domainId(), context.agentId());
        String documentId = "mexp-" + hash(List.of(context.ownerId(), context.domainId(), context.agentId(), experience.experienceId()));
        if (experience.state() != MemoryState.ACTIVE) {
            projections.publish(space, documentId, SCHEMA, null);
            return;
        }
        Map<String, RagValue> attrs = privateAttributes(context, experience.version().revision(), experience.version().contentHash());
        attrs.put("experienceId", RagValue.string(experience.experienceId()));
        String content = "Scenario: " + experience.scenario() + "\nApplicability: " + experience.applicability()
                + "\nConclusion: " + experience.conclusion();
        requireBodyBound(content);
        projections.publish(space, documentId, SCHEMA,
                new RagDocument("memory_experience", experience.experienceId(), content, attrs));
    }

    public void removeExperience(String ownerId, String domainId, String agentId, String experienceId) {
        if (!enabled()) return;
        String space = MemoryRagSpaceIds.spaceId(MemoryRagRecordType.EXPERIENCE, domainId, agentId);
        String documentId = "mexp-" + hash(List.of(ownerId, domainId, agentId, experienceId));
        projections.publish(space, documentId, SCHEMA, null);
    }

    /** The current summary is projected to distinct session and summary spaces with identical source truth. */
    public void summary(MemorySummary summary) {
        if (!enabled()) return;
        MemoryContextKey context = summary.contextKey();
        String content = summary.items().stream().map(item -> item.kind().name() + ": " + item.text())
                .reduce((left, right) -> left + "\n" + right).orElse("");
        requireBodyBound(content);
        for (MemoryRagRecordType type : List.of(MemoryRagRecordType.SESSION, MemoryRagRecordType.SUMMARY)) {
            String space = MemoryRagSpaceIds.spaceId(type, context.domainId(), context.agentId());
            String documentId = summaryDocumentId(type, context, summary.summaryId());
            Map<String, RagValue> attrs = privateAttributes(context, summary.version().revision(), summary.version().contentHash());
            attrs.put("summaryId", RagValue.string(summary.summaryId()));
            attrs.put("sessionId", RagValue.string(context.conversationId()));
            projections.publish(space, documentId, SCHEMA,
                    new RagDocument(type == MemoryRagRecordType.SESSION ? "memory_session" : "memory_summary",
                            summary.summaryId(), content, attrs));
        }
    }

    public void removeSummary(MemoryContextKey context, String summaryId) {
        if (!enabled()) return;
        for (MemoryRagRecordType type : List.of(MemoryRagRecordType.SESSION, MemoryRagRecordType.SUMMARY)) {
            projections.publish(MemoryRagSpaceIds.spaceId(type, context.domainId(), context.agentId()),
                    summaryDocumentId(type, context, summaryId), SCHEMA, null);
        }
    }

    private List<String> fragments(PreferenceBlock block, List<PreferenceTransition> history) {
        List<String> entries = new ArrayList<>();
        for (PreferenceTransition transition : history) {
            String value = displayValue(transition.value());
            entries.add(transition.historyOrdinal() + ". " + transition.change() + " " + transition.polarity()
                    + "; value=" + value + "; applicability=" + Objects.toString(transition.applicability(), "")
                    + "; occurred=" + transition.occurredAt() + "; effective=" + transition.effectiveFrom()
                    + ".." + Objects.toString(transition.effectiveUntil(), "")
                    + (transition.matterId() == null ? "" : "; matter=" + transition.matterId()));
        }
        List<String> chunks = new ArrayList<>();
        String currentValue = block.currentState().value() == null ? "none" : displayValue(block.currentState().value());
        String header = "Preference " + block.key().dimensionId() + ", revision " + block.version().revision()
                + ", status=" + block.currentState().status() + ", current=" + currentValue
                + ", polarity=" + Objects.toString(block.currentState().polarity(), "none")
                + ", confidence=" + block.currentState().confidence()
                + ", activation=" + block.currentState().activation();
        String current = header + "\n";
        for (String entry : entries) {
            String separator = current.endsWith("\n") ? "" : "\n";
            String next = current + separator + entry;
            if (utf8(next) > MAX_FRAGMENT_BYTES && !current.endsWith("\n")) {
                chunks.add(current);
                current = header + " (continued)\n" + entry;
            } else current = next;
            if (utf8(current) > MAX_FRAGMENT_BYTES) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        }
        if (!current.isBlank()) chunks.add(current);
        return chunks.isEmpty() ? List.of("Preference " + block.key().dimensionId() + ", revision " + block.version().revision()) : chunks;
    }

    private static String displayValue(MemoryJsonValue value) {
        return switch (value.type()) {
            case NULL -> "null";
            case STRING, NUMBER, BOOLEAN -> value.scalar();
            case ARRAY -> value.elements().stream().map(MemoryV2RagProjectionService::displayValue)
                    .reduce((left, right) -> left + ", " + right).map(items -> "[" + items + "]").orElse("[]");
            case OBJECT -> value.properties().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + ": " + displayValue(entry.getValue()))
                    .reduce((left, right) -> left + ", " + right).map(items -> "{" + items + "}").orElse("{}");
        };
    }

    private void removeStale(String space, String prefix, Set<String> liveIds) {
        List<String> existing = jdbc.queryForList("SELECT document_id FROM memory_rag_projection WHERE space_id=? AND document_id LIKE ? AND tombstone=FALSE",
                String.class, space, prefix + "%");
        for (String id : existing) if (!liveIds.contains(id)) projections.publish(space, id, SCHEMA, null);
    }

    private static Map<String, RagValue> privateAttributes(MemoryContextKey context, long version, String sourceHash) {
        Map<String, RagValue> attrs = new LinkedHashMap<>();
        attrs.put("ownerId", RagValue.string(context.ownerId()));
        attrs.put("domainId", RagValue.string(context.domainId()));
        attrs.put("agentId", RagValue.string(context.agentId()));
        attrs.put("active", RagValue.bool(true));
        attrs.put("sourceVersion", RagValue.number(version));
        attrs.put("sourceHash", RagValue.string(sourceHash));
        return attrs;
    }

    private static String summaryDocumentId(MemoryRagRecordType type, MemoryContextKey context, String summaryId) {
        return "m" + type.id().replace("-", "") + "-" + hash(List.of(context.ownerId(), context.domainId(),
                context.agentId(), context.conversationId(), summaryId));
    }

    private static String hash(Object value) { return MemoryCanonicalHash.contentHash(value); }
    private static int utf8(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static void requireBodyBound(String content) {
        if (content == null || content.isBlank() || utf8(content) > 30 * 1024) {
            throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        }
    }
}
