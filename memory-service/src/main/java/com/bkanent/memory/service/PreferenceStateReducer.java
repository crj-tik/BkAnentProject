package com.bkanent.memory.service;

import com.bkanent.common.memory.*;
import java.time.Instant;
import java.util.*;

/** Pure, deterministic source ordering. Processing order and cross-conversation sequence comparisons grant no precedence. */
final class PreferenceStateReducer {
    PreferenceCurrentState reduce(List<PreferenceTransition> history, Instant asOf, String matter) {
        var active = history.stream().filter(t -> t.assessment() == PreferenceTransition.Assessment.PREFERENCE
                && !t.effectiveFrom().isAfter(asOf) && (t.effectiveUntil() == null || t.effectiveUntil().isAfter(asOf))
                && (t.matterId() == null || t.matterId().equals(matter))).toList();
        var byId = new HashMap<String,PreferenceTransition>(); history.forEach(t -> byId.put(t.transitionId(), t));
        var base = frontier(active.stream().filter(t -> t.change() != PreferenceTransition.Change.TEMPORARY_EXCEPTION).toList(), byId);
        var exceptions = frontier(active.stream().filter(t -> t.change() == PreferenceTransition.Change.TEMPORARY_EXCEPTION).toList(), byId);
        var selected = exceptions.isEmpty() ? base : exceptions;
        selected = selected.stream().filter(t -> t.change() != PreferenceTransition.Change.WITHDRAWAL).toList();
        if (selected.isEmpty()) return new PreferenceCurrentState(PreferenceCurrentState.Status.NO_EFFECTIVE_VALUE, null, List.of(),
                List.of(), List.of(), 0, 0, null, List.of(), null);
        var first = selected.get(0);
        boolean conflict = selected.stream().anyMatch(t -> t.change() == PreferenceTransition.Change.UNRESOLVED_CONFLICT
                || !t.value().equals(first.value()) || t.polarity() != first.polarity());
        var dependencies = selected.stream().flatMap(t -> t.evidence().stream()).distinct().toList();
        var ids = selected.stream().map(PreferenceTransition::transitionId).toList();
        return new PreferenceCurrentState(conflict ? PreferenceCurrentState.Status.UNRESOLVED : PreferenceCurrentState.Status.EFFECTIVE,
                conflict ? null : first.value(), conflict ? List.of() : ids,
                exceptions.stream().map(PreferenceTransition::transitionId).toList(), conflict ? ids : List.of(),
                selected.stream().mapToDouble(PreferenceTransition::confidence).max().orElse(0), 1,
                selected.stream().map(PreferenceTransition::occurredAt).max(Instant::compareTo).orElseThrow(), dependencies,
                conflict ? null : first.polarity());
    }
    private List<PreferenceTransition> frontier(List<PreferenceTransition> values, Map<String,PreferenceTransition> byId) {
        List<PreferenceTransition> result = new ArrayList<>();
        for (var value : values) {
            if (result.stream().anyMatch(existing -> dominates(existing, value, byId))) continue;
            result.removeIf(existing -> dominates(value, existing, byId)); result.add(value);
            if (result.size() > 500) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        }
        return result.stream().sorted(Comparator.comparing(PreferenceTransition::transitionId)).toList();
    }
    private boolean dominates(PreferenceTransition later, PreferenceTransition earlier, Map<String,PreferenceTransition> byId) {
        var left = later.evidence().get(0).source(); var right = earlier.evidence().get(0).source();
        if (later.change() != PreferenceTransition.Change.WITHDRAWAL && left.contextKey().equals(right.contextKey())
                && left.sourceSequence() > right.sourceSequence()) return true;
        Set<String> visited = new HashSet<>(); Deque<String> todo = new ArrayDeque<>(later.correctsTransitionIds());
        while (!todo.isEmpty()) {
            String id = todo.removeFirst(); if (!visited.add(id)) continue;
            if (id.equals(earlier.transitionId())) return true;
            var transition = byId.get(id); if (transition != null) todo.addAll(transition.correctsTransitionIds());
        }
        return false;
    }
}
