package com.bkanent.agent.memory;

import com.bkanent.common.memory.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Function;

/** Exact, scoped evidence resolution. An ambiguous name never becomes entity identity. */
public final class SupervisorMemoryReferentResolver {
    public static final String TOOL_NAME = "memory_resolve_reference";
    public static final String TOOL_SCHEMA = "{\"type\":\"object\",\"properties\":{\"reference\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":255},\"matterId\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":255}},\"required\":[\"reference\"],\"additionalProperties\":false}";
    public record Request(String reference, String matterId) { }
    public record Result(String status, List<MemoryEntityAnchor> candidates, List<MemorySourceDependency> dependencies, boolean remoteLookup) { }
    public Request parse(ObjectMapper mapper, String arguments) {
        try {
            var tree = mapper.readTree(arguments);
            if (tree == null || !tree.isObject() || tree.size() < 1 || tree.size() > 2) throw new IllegalArgumentException();
            var fields = tree.fieldNames();
            while (fields.hasNext()) if (!Set.of("reference", "matterId").contains(fields.next())) throw new IllegalArgumentException();
            if (!tree.path("reference").isTextual() || tree.path("reference").asText().isBlank() || tree.path("reference").asText().length() > 255) throw new IllegalArgumentException();
            String matter = tree.has("matterId") ? tree.path("matterId").textValue() : null;
            if (tree.has("matterId") && (matter == null || matter.isBlank() || matter.length() > 255)) throw new IllegalArgumentException();
            return new Request(tree.path("reference").textValue(), matter);
        } catch (Exception exception) { throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT); }
    }
    public Result resolve(MemoryWorkingSetResponse snapshot, Request request, Function<String, MemoryEntityQueryResponse> page) {
        Map<String, MemoryEntityAnchor> anchors = new LinkedHashMap<>();
        snapshot.summaries().forEach(s -> s.entities().forEach(e -> adopt(snapshot.contextKey(), anchors, e)));
        var local = matches(anchors.values(), request);
        if (!local.isEmpty()) return result(local.size() == 1 ? "RESOLVED" : "NEEDS_CLARIFICATION", local, local, false);
        String cursor = null;
        List<MemoryEntityAnchor> inspected = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            var response = page.apply(cursor);
            if (response.entities().size() > 20) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
            response.entities().forEach(e -> adopt(snapshot.contextKey(), anchors, e));
            inspected.addAll(response.entities());
            if (!response.hasMore()) {
                var matches = matches(anchors.values(), request);
                return result(matches.size() == 1 ? "RESOLVED" : "NEEDS_CLARIFICATION", matches, inspected, true);
            }
            if (Objects.equals(cursor, response.nextCursor())) throw new MemoryException(MemoryErrorCode.CURSOR_INVALID);
            cursor = response.nextCursor();
        }
        return result("INCOMPLETE_NEEDS_CLARIFICATION", matches(anchors.values(), request), inspected, true);
    }
    private void adopt(MemoryContextKey context, Map<String, MemoryEntityAnchor> anchors, MemoryEntityAnchor entity) {
        if (!context.equals(entity.contextKey()) || !MemoryCanonicalHash.entityHash(entity).equals(entity.version().contentHash())) throw new MemoryException(MemoryErrorCode.FORBIDDEN);
        var old = anchors.putIfAbsent(entity.entityId(), entity);
        if (old != null && !old.equals(entity)) throw new MemoryException(MemoryErrorCode.VERSION_CONFLICT);
    }
    private List<MemoryEntityAnchor> matches(Collection<MemoryEntityAnchor> anchors, Request request) {
        return anchors.stream().filter(e -> request.matterId() == null || request.matterId().equals(e.matterId()))
                .filter(e -> e.entityId().equals(request.reference()) || e.aliases().contains(request.reference())).toList();
    }
    private Result result(String status, List<MemoryEntityAnchor> candidates, List<MemoryEntityAnchor> inspected, boolean remote) {
        Set<MemorySourceDependency> dependencies = new LinkedHashSet<>();
        inspected.forEach(e -> dependencies.addAll(e.dependencies()));
        if (dependencies.size() > 500 || candidates.size() > 20) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        return new Result(status, candidates, List.copyOf(dependencies), remote);
    }
}
