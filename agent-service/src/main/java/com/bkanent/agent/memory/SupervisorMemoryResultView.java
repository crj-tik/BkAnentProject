package com.bkanent.agent.memory;

import com.bkanent.common.memory.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/** Prompt-only projection. Complete archive DTOs and their hashes are never rewritten. */
public final class SupervisorMemoryResultView {
    public static final String TOOL_NAME = "memory_read_result";
    public static final String TOOL_SCHEMA = """
            {"type":"object","properties":{"eventId":{"type":"string"},"callId":{"type":"string"},"referenceId":{"type":"string"},"pointer":{"type":"string"},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":4096}},"required":["eventId","callId","referenceId"],"additionalProperties":false}
            """;
    public record Read(String eventId, String callId, String referenceId, String pointer, int offset, int limit) { }
    private final ObjectMapper mapper;
    private final int inlineChars;
    private final int outputChars;

    public SupervisorMemoryResultView(ObjectMapper mapper, int inlineChars, int outputChars) {
        this.mapper = mapper; this.inlineChars = inlineChars; this.outputChars = outputChars;
    }

    public static String referenceId(MemoryToolResultAnchor anchor) {
        return "r-" + MemoryCanonicalHash.contentHash(List.of(anchor.source(), anchor.callId()));
    }

    public Map<String,Object> reference(MemoryToolResultAnchor anchor) {
        return Map.of("dataForm", "REFERENCED_COMPLETE", "inlineComplete", false, "archiveComplete", true,
                "referenceId", referenceId(anchor), "eventId", anchor.source().sourceEventId(), "callId", anchor.callId(),
                "toolName", anchor.toolName(), "state", anchor.state(), "source", anchor.source(), "reader", TOOL_NAME);
    }

    public Object response(MemoryMessage message, MemoryToolResponse response) {
        if (response.dataForm() != MemoryToolResponse.DataForm.INLINE_COMPLETE) throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
        Object value = plain(response.responseData());
        if (!large(response)) return value;
        if (response.dataForm() != MemoryToolResponse.DataForm.INLINE_COMPLETE) throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
        return reference(new MemoryToolResultAnchor(message.source(), response.callId(), response.toolName(), response.state()));
    }

    public JsonNode historical(MemoryMessage message) {
        ObjectNode view = mapper.valueToTree(message);
        for (int i = 0; i < message.toolResponses().size(); i++) {
            var response = message.toolResponses().get(i);
            var projected = response(message, response);
            ObjectNode item = (ObjectNode) view.path("toolResponses").get(i);
            item.set("responseData", mapper.valueToTree(projected));
            if (large(response)) {
                item.put("dataForm", "REFERENCED_COMPLETE");
                item.set("fullDataRef", mapper.valueToTree(message.source()));
            }
        }
        return view;
    }

    public List<MemoryToolResultAnchor> anchors(List<MemoryMessage> messages) {
        List<MemoryToolResultAnchor> results = new ArrayList<>();
        for (var message : messages) for (var response : message.toolResponses()) {
            if (large(response)) {
                if (response.dataForm() != MemoryToolResponse.DataForm.INLINE_COMPLETE) throw new MemoryException(MemoryErrorCode.ARCHIVE_NOT_CONFIRMED);
                results.add(new MemoryToolResultAnchor(message.source(), response.callId(), response.toolName(), response.state()));
            }
        }
        return List.copyOf(results);
    }

    private boolean large(MemoryToolResponse response) {
        int length = json(plain(response.responseData())).length();
        if (TOOL_NAME.equals(response.toolName())) {
            if (length > outputChars) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
            return false; // This control node already emitted a bounded page; do not reference the page again.
        }
        return length > inlineChars;
    }

    public Read parse(String arguments) {
        try {
            if (arguments == null || arguments.length() > 4096) throw invalid();
            JsonNode input = mapper.readTree(arguments);
            if (input == null || !input.isObject()) throw invalid();
            Set<String> allowed = Set.of("eventId", "callId", "referenceId", "pointer", "offset", "limit");
            input.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw invalid(); });
            String event = text(input, "eventId"), call = text(input, "callId"), ref = text(input, "referenceId");
            if (!event.matches("[a-f0-9]{64}") || call.isBlank() || call.length() > 255 || !ref.matches("r-[a-f0-9]{64}")) throw invalid();
            String pointer = input.has("pointer") ? text(input, "pointer") : "";
            if (pointer.length() > 512 || !pointer.isEmpty() && !pointer.startsWith("/")) throw invalid();
            int offset = integer(input, "offset", 0), limit = integer(input, "limit", 1024);
            if (offset < 0 || limit < 1 || limit > 4096) throw invalid();
            return new Read(event, call, ref, pointer, offset, limit);
        } catch (MemoryException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }

    public String read(MemoryMessage message, Read request) {
        if (!message.source().sourceEventId().equals(request.eventId())) throw invalid();
        var response = message.toolResponses().stream().filter(r -> r.callId().equals(request.callId())).findFirst()
                .orElseThrow(() -> new MemoryException(MemoryErrorCode.NOT_FOUND));
        var anchor = new MemoryToolResultAnchor(message.source(), response.callId(), response.toolName(), response.state());
        if (!referenceId(anchor).equals(request.referenceId()) || response.dataForm() != MemoryToolResponse.DataForm.INLINE_COMPLETE) throw invalid();
        JsonNode selected = mapper.valueToTree(plain(response.responseData())).at(request.pointer());
        if (selected.isMissingNode()) throw new MemoryException(MemoryErrorCode.NOT_FOUND);
        String serialized = json(selected);
        int start = request.offset();
        if (start > serialized.length() || start > 0 && start < serialized.length() && Character.isLowSurrogate(serialized.charAt(start))) throw invalid();
        int end = (int) Math.min(serialized.length(), (long) start + request.limit());
        if (end < serialized.length() && end > start && Character.isHighSurrogate(serialized.charAt(end - 1))) end--;
        if (end == start && start < serialized.length()) throw invalid();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("reference", reference(anchor)); result.put("pointer", request.pointer()); result.put("encoding", "json-utf16");
        result.put("offset", start); result.put("totalChars", serialized.length());
        while (true) {
            result.put("chunk", serialized.substring(start, end)); result.put("nextOffset", end);
            result.put("complete", start == 0 && end == serialized.length()); result.put("hasMore", end < serialized.length());
            String output = json(result);
            if (output.length() <= outputChars) return output;
            if (end == start) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
            end = start + (end - start) / 2;
            if (end > start && Character.isHighSurrogate(serialized.charAt(end - 1))) end--;
            if (end == start && start < serialized.length()) throw new MemoryException(MemoryErrorCode.CONTEXT_BUDGET_EXCEEDED);
        }
    }

    public static Object plain(MemoryJsonValue value) {
        if (value == null) return null;
        return switch (value.type()) {
            case NULL -> null;
            case STRING -> value.scalar();
            case NUMBER -> new java.math.BigDecimal(value.scalar());
            case BOOLEAN -> Boolean.parseBoolean(value.scalar());
            case ARRAY -> value.elements().stream().map(SupervisorMemoryResultView::plain).toList();
            case OBJECT -> { Map<String,Object> fields = new LinkedHashMap<>(); value.properties().forEach((key, child) -> fields.put(key, plain(child))); yield fields; }
        };
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw invalid(); } }
    private String text(JsonNode input, String key) { if (!input.path(key).isTextual()) throw invalid(); return input.get(key).textValue(); }
    private int integer(JsonNode input, String key, int fallback) {
        if (!input.has(key)) return fallback;
        if (!input.get(key).isIntegralNumber() || !input.get(key).canConvertToInt()) throw invalid();
        return input.get(key).intValue();
    }
    private MemoryException invalid() { return new MemoryException(MemoryErrorCode.INVALID_ARGUMENT); }
}
