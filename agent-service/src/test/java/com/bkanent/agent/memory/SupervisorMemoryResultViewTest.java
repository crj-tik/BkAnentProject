package com.bkanent.agent.memory;

import com.bkanent.agent.orchestration.StoredModelMessage;
import com.bkanent.common.memory.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SupervisorMemoryResultViewTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final SupervisorMemoryResultView view = new SupervisorMemoryResultView(mapper, 256, 2048);
    final MemoryContextKey context = new MemoryContextKey("owner", "supervisor", "supervisor-agent", "session");
    MemoryMessage message(String tool, String data) {
        return new SupervisorMemoryProjection(mapper).project(context, "run", "a".repeat(64), 1,
                StoredModelMessage.tools(List.of(new ToolResponseMessage.ToolResponse("call", tool, data))));
    }
    String arguments(MemoryMessage message, String pointer, int offset, int limit) throws Exception {
        var anchor = view.anchors(List.of(message)).get(0);
        return mapper.writeValueAsString(Map.of("eventId", anchor.source().sourceEventId(), "callId", "call",
                "referenceId", SupervisorMemoryResultView.referenceId(anchor), "pointer", pointer, "offset", offset, "limit", limit));
    }
    @Test void promptReferencePreservesOriginalCompleteStructuredArchiveAndState() {
        var message = message("find", "{\"detail\":\"" + "原文".repeat(300) + "\",\"number\":12}");
        String hash = MemoryCanonicalHash.messageHash(message);
        var projected = view.historical(message);
        assertEquals("REFERENCED_COMPLETE", projected.at("/toolResponses/0/dataForm").asText());
        assertEquals("RETURNED", projected.at("/toolResponses/0/responseData/state").asText());
        assertFalse(projected.toString().contains("原文"));
        assertEquals(MemoryToolResponse.DataForm.INLINE_COMPLETE, message.toolResponses().get(0).dataForm());
        assertEquals(hash, MemoryCanonicalHash.messageHash(message));
        assertEquals("12", message.toolResponses().get(0).responseData().properties().get("number").scalar());
    }
    @Test void boundedEscapedPagesReassembleExactSelectedJsonWithoutSplittingSurrogates() throws Exception {
        String content = "😀\n\"\\".repeat(300);
        var message = message("find", mapper.writeValueAsString(Map.of("detail", content)));
        String expected = mapper.writeValueAsString(content);
        StringBuilder restored = new StringBuilder(); int offset = 0;
        do {
            String output = view.read(message, view.parse(arguments(message, "/detail", offset, 4096)));
            assertTrue(output.length() <= 2048);
            var page = mapper.readTree(output);
            String chunk = page.get("chunk").asText();
            assertFalse(!chunk.isEmpty() && Character.isLowSurrogate(chunk.charAt(0)));
            assertFalse(!chunk.isEmpty() && Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
            restored.append(chunk);
            assertTrue(page.get("nextOffset").asInt() > offset);
            offset = page.get("nextOffset").asInt();
            assertEquals(offset < expected.length(), page.get("hasMore").asBoolean());
        } while (offset < expected.length());
        assertEquals(expected, restored.toString());
    }
    @Test void rejectsForgedRefsUnknownPointersAndMalformedArguments() throws Exception {
        var message = message("find", "{\"detail\":\"" + "x".repeat(1000) + "\"}");
        String args = arguments(message, "/detail", 0, 40);
        var tree = mapper.readTree(args); ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("referenceId", "r-" + "b".repeat(64));
        assertCode(MemoryErrorCode.INVALID_ARGUMENT, () -> view.read(message, view.parse(tree.toString())));
        assertCode(MemoryErrorCode.NOT_FOUND, () -> view.read(message, view.parse(arguments(message, "/absent", 0, 40))));
        for (String invalid : List.of("{}", args.replace("\"limit\":40", "\"limit\":0"), args.replace("\"offset\":0", "\"offset\":1.5"), args.replaceFirst("\\{", "{\"owner\":\"other\",")))
            assertCode(MemoryErrorCode.INVALID_ARGUMENT, () -> view.parse(invalid));
    }
    @Test void boundedRecallPagesStayInlineEvenAboveBusinessInlineLimit() {
        var message = message(SupervisorMemoryResultView.TOOL_NAME, "{\"chunk\":\"" + "x".repeat(1000) + "\"}");
        assertTrue(view.anchors(List.of(message)).isEmpty());
        assertEquals("x".repeat(1000), view.historical(message).at("/toolResponses/0/responseData/chunk").asText());
    }
    @Test void invalidUtf16PageStartAndTooSmallSurrogatePageAreRejected() throws Exception {
        var message = message("find", mapper.writeValueAsString("😀".repeat(300)));
        assertCode(MemoryErrorCode.INVALID_ARGUMENT, () -> view.read(message, view.parse(arguments(message, "", 2, 30))));
        assertCode(MemoryErrorCode.INVALID_ARGUMENT, () -> view.read(message, view.parse(arguments(message, "", 1, 1))));
    }
    static void assertCode(MemoryErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(MemoryException.class, action).code());
    }
}
