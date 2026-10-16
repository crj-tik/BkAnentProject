package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** Versioned candidate/committed content. Durable coverage and CAS must be checked by the owning service. */
public record MemorySummary(String summaryId, MemoryContextKey contextKey, MemoryAssociation association,
                            MemoryRevision version, long expectedConversationRevision, MemoryRunVersion runVersion,
                            MemoryCoveredRange coveredRange, List<MemoryCoveredRange> toolGroupRanges,
                            List<Item> items, List<MemoryEntityAnchor> entities,
                            List<MemorySourceDependency> dependencies) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum ItemKind { GOAL, FACT, DECISION, NEGATION, CORRECTION, EXCEPTION, UNRESOLVED }

    public record Item(ItemKind kind, String text, List<MemorySourceDependency> evidence) implements Serializable {
        private static final long serialVersionUID = 1L;
        public Item {
            MemoryContract.required(kind, MemoryException.Field.SUMMARY);
            text = MemoryContract.text(text, MemoryException.Field.TEXT);
            evidence = MemoryContract.nonempty(evidence, MemoryException.Field.EVIDENCE);
        }
    }

    public MemorySummary {
        summaryId = MemoryContract.id(summaryId, MemoryException.Field.SUMMARY);
        MemoryContract.required(contextKey, MemoryException.Field.IDENTITY);
        MemoryContract.required(association, MemoryException.Field.ASSOCIATION);
        MemoryContract.required(version, MemoryException.Field.VERSION);
        MemoryContract.require(expectedConversationRevision >= 0, MemoryException.Field.VERSION);
        MemoryContract.require(runVersion == null || runVersion.runId().equals(association.runId()), MemoryException.Field.VERSION);
        MemoryContract.required(coveredRange, MemoryException.Field.COVERAGE);
        toolGroupRanges = MemoryContract.list(toolGroupRanges, MemoryException.Field.TOOL_GROUP);
        long previous = 0;
        for (MemoryCoveredRange range : toolGroupRanges) {
            MemoryContract.require(coveredRange.contains(range.fromSequence()) && coveredRange.contains(range.throughSequence())
                    && range.fromSequence() > previous, MemoryException.Field.TOOL_GROUP);
            previous = range.throughSequence();
        }
        items = MemoryContract.nonempty(items, MemoryException.Field.SUMMARY);
        entities = MemoryContract.list(entities, MemoryException.Field.SUMMARY);
        dependencies = MemoryContract.nonempty(dependencies, MemoryException.Field.SOURCE);
        int characters = 0;
        for (Item item : items) {
            characters += item.text().length();
            MemoryContract.require(dependencies.containsAll(item.evidence()), MemoryException.Field.EVIDENCE);
        }
        for (MemoryEntityAnchor entity : entities) {
            characters += entity.briefDefinition().length();
            MemoryContract.require(entity.contextKey().equals(contextKey) && dependencies.containsAll(entity.dependencies()), MemoryException.Field.SOURCE);
        }
        MemoryContract.require(characters <= MemoryContract.MAX_TEXT, MemoryException.Field.SUMMARY);
    }
}
