package com.bkanent.agent.memory;

import com.bkanent.agent.orchestration.StoredModelMessage;
import com.bkanent.common.memory.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Pattern;

/** Projects persisted graph facts, never model-supplied owner/domain or a streaming fragment. */
public final class SupervisorMemoryProjection {
    private static final Pattern CREDENTIAL_TEXT = Pattern.compile(
            "(?i)(?:\\bBearer\\s+\\S+|\\b(?:password|passwd|access[_-]?token|refresh[_-]?token|client[_-]?secret|authorization)\\b\\s*[:=]\\s*\\S+|\\beyJ[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{12,}\\.[A-Za-z0-9_-]{8,})");
    private final ObjectMapper mapper;
    public SupervisorMemoryProjection(ObjectMapper mapper) { this.mapper = mapper; }

    public MemoryMessage project(MemoryContextKey context, String runId, String event, long sequence, StoredModelMessage stored) {
        if (stored.matterIntent() != null && stored.matterIntent().goal() != null) requireSafeText(stored.matterIntent().goal());
        var association = new MemoryAssociation(stored.matterId(), runId, null, null);
        MemoryMessage.Role role = switch (stored.role()) {
            case "user" -> MemoryMessage.Role.USER;
            case "assistant" -> MemoryMessage.Role.ASSISTANT;
            case "tool" -> MemoryMessage.Role.TOOL;
            default -> throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT);
        };
        var calls = stored.calls() == null ? List.<MemoryToolCall>of() : stored.calls().stream().map(call ->
                new MemoryToolCall(call.id(), "supervisor-tool:" + call.name(), call.name(), parse(call.arguments(), true))).toList();
        var responses = stored.responses() == null ? List.<MemoryToolResponse>of() : stored.responses().stream().map(response ->
                new MemoryToolResponse(response.id(), response.name(), responseState(response.responseData()), null,
                        MemoryToolResponse.DataForm.INLINE_COMPLETE, parse(response.responseData(), false), null, List.of())).toList();
        String text = role == MemoryMessage.Role.TOOL || stored.text() == null || stored.text().isEmpty() ? null : safeText(stored.text());
        String id = "m-" + event;
        var source = new MemorySourceRef(MemorySourceRef.Kind.MESSAGE, context, id, event, sequence, new MemoryRevision(1, "0".repeat(64), "supervisor.message.v2"));
        var draft = new MemoryMessage(id, context, sequence, association, role, text, calls, responses, source, stored.memoryDependencies(), stored.matterIntent());
        return new MemoryMessage(id, context, sequence, association, role, text, calls, responses,
                new MemorySourceRef(source.kind(), context, id, event, sequence,
                        new MemoryRevision(1, MemoryCanonicalHash.messageHash(draft), source.version().schemaVersion())), stored.memoryDependencies(), stored.matterIntent());
    }

    private MemoryToolResponse.ExecutionState responseState(String text) {
        if (text != null && text.startsWith("OUTCOME_UNKNOWN")) return MemoryToolResponse.ExecutionState.OUTCOME_UNKNOWN;
        try {
            var tree = mapper.readTree(text);
            String status = tree.path("status").asText("");
            return switch (status) {
                case "FAILED", "REJECTED" -> MemoryToolResponse.ExecutionState.FAILED;
                case "OUTCOME_UNKNOWN" -> MemoryToolResponse.ExecutionState.OUTCOME_UNKNOWN;
                case "WAITING_USER_APPROVAL" -> MemoryToolResponse.ExecutionState.WAITING_APPROVAL;
                // A returned JSON status is not an accepted-invocation ledger proof.
                case "ACCEPTED", "RUNNING" -> MemoryToolResponse.ExecutionState.RETURNED;
                case "PARTIAL" -> MemoryToolResponse.ExecutionState.PARTIAL;
                default -> MemoryToolResponse.ExecutionState.RETURNED;
            };
        } catch (Exception ignored) { return MemoryToolResponse.ExecutionState.RETURNED; }
    }

    private MemoryJsonValue parse(String text, boolean object) {
        try {
            JsonNode node = mapper.readTree(text);
            if (node == null || object && !node.isObject()) throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT);
            return value(node, 0);
        } catch (MemoryException exception) { throw exception; }
        catch (Exception exception) {
            if (object) throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT);
            return MemoryJsonValue.string(safeText(text == null ? "" : text));
        }
    }
    private MemoryJsonValue value(JsonNode node, int depth) {
        if (depth > 8) throw new MemoryException(MemoryErrorCode.INVALID_ARGUMENT);
        if (node.isObject()) {
            Map<String,MemoryJsonValue> fields = new LinkedHashMap<>();
            node.fields().forEachRemaining(field -> {
                if (field.getKey().matches("(?i).*(token|password|secret|beacon|authorization|desensiti[sz]ationmap).*")) throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION);
                fields.put(field.getKey(), value(field.getValue(), depth + 1));
            });
            return MemoryJsonValue.object(fields);
        }
        if (node.isArray()) { List<MemoryJsonValue> values = new ArrayList<>(); node.forEach(item -> values.add(value(item, depth + 1))); return MemoryJsonValue.array(values); }
        if (node.isTextual()) return MemoryJsonValue.string(safeText(node.textValue()));
        if (node.isNumber()) return MemoryJsonValue.number(node.decimalValue().toPlainString());
        if (node.isBoolean()) return MemoryJsonValue.bool(node.booleanValue());
        return MemoryJsonValue.nil();
    }
    private String safeText(String text) {
        return requireSafeText(text);
    }
    public static String requireSafeText(String text) {
        if (CREDENTIAL_TEXT.matcher(text).find()) throw new MemoryException(MemoryErrorCode.INVALID_EXTRACTION);
        return text;
    }
}
