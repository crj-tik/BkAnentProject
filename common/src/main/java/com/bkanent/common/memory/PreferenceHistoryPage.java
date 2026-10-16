package com.bkanent.common.memory;

import java.io.Serializable;
import java.util.List;

/** COMPLETE means the fixed authorized history has been read through this final page, not all private evidence exists. */
public record PreferenceHistoryPage(PreferenceHistoryCursor cursor, List<PreferenceTransition> transitions,
                                    Long coveredFromOrdinal, Long coveredThroughOrdinal, boolean hasMore,
                                    PreferenceHistoryCursor nextCursor, Completeness completeness) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Completeness { PARTIAL, COMPLETE, UNAVAILABLE }
    public PreferenceHistoryPage {
        MemoryContract.required(cursor, MemoryException.Field.CURSOR);
        transitions = MemoryContract.list(transitions, MemoryException.Field.PAGE);
        MemoryContract.required(completeness, MemoryException.Field.PAGE);
        long previous = cursor.afterOrdinal();
        for (PreferenceTransition transition : transitions) {
            MemoryContract.require(transition.historyOrdinal() > previous
                    && transition.introducedRevision() <= cursor.revision(), MemoryException.Field.PAGE);
            previous = transition.historyOrdinal();
        }
        MemoryContract.require(transitions.stream().map(PreferenceTransition::transitionId).distinct().count() == transitions.size(), MemoryException.Field.PAGE);
        MemoryContract.require(transitions.isEmpty() ? coveredFromOrdinal == null && coveredThroughOrdinal == null
                : Long.valueOf(transitions.get(0).historyOrdinal()).equals(coveredFromOrdinal)
                && Long.valueOf(previous).equals(coveredThroughOrdinal), MemoryException.Field.COVERAGE);
        MemoryContract.require(hasMore ? nextCursor != null && !transitions.isEmpty()
                && nextCursor.blockId().equals(cursor.blockId()) && nextCursor.revision() == cursor.revision()
                && nextCursor.authorizationRevision() == cursor.authorizationRevision()
                && nextCursor.afterOrdinal() == previous : nextCursor == null, MemoryException.Field.CURSOR);
        MemoryContract.require(completeness != Completeness.COMPLETE || !hasMore, MemoryException.Field.PAGE);
        MemoryContract.require(completeness != Completeness.UNAVAILABLE || transitions.isEmpty() && !hasMore, MemoryException.Field.PAGE);
    }
}
