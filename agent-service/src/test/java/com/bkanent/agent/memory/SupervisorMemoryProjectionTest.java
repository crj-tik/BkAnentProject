package com.bkanent.agent.memory;

import com.bkanent.agent.orchestration.StoredModelMessage;
import com.bkanent.common.memory.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SupervisorMemoryProjectionTest {
    private final SupervisorMemoryProjection projection = new SupervisorMemoryProjection(new ObjectMapper().findAndRegisterModules());
    private final MemoryContextKey context = new MemoryContextKey("owner", "supervisor", "supervisor-agent", "session");

    @Test void callbackReturnStatusDoesNotBecomeAcceptedBusinessExecution() {
        var stored = StoredModelMessage.tools(List.of(new ToolResponseMessage.ToolResponse("call-1", "submit",
                "{\"status\":\"ACCEPTED\",\"result\":{\"id\":7}}")));
        var projected = projection.project(context, "run", "event-1", 1, stored);
        assertEquals(MemoryToolResponse.ExecutionState.RETURNED, projected.toolResponses().get(0).state());
        assertEquals("7", projected.toolResponses().get(0).responseData().properties().get("result").properties().get("id").scalar());
    }

    @Test void credentialShapedStructuredToolOutputIsRejectedBeforeArchiving() {
        var stored = StoredModelMessage.tools(List.of(new ToolResponseMessage.ToolResponse("call-1", "submit",
                "{\"accessToken\":\"private\"}")));
        var failure = assertThrows(MemoryException.class, () -> projection.project(context, "run", "event-1", 1, stored));
        assertEquals(MemoryErrorCode.INVALID_EXTRACTION, failure.code());
    }

    @Test void bearerAndInlinePasswordTextAreRejectedBeforeArchiving() {
        var bearer = StoredModelMessage.user("Use Bearer abcdefghijklmnopqrstuvwxyz");
        assertEquals(MemoryErrorCode.INVALID_EXTRACTION,
                assertThrows(MemoryException.class, () -> projection.project(context, "run", "event-1", 1, bearer)).code());
        var password = StoredModelMessage.tools(List.of(new ToolResponseMessage.ToolResponse("call-1", "submit", "password=hunter2")));
        assertEquals(MemoryErrorCode.INVALID_EXTRACTION,
                assertThrows(MemoryException.class, () -> projection.project(context, "run", "event-2", 2, password)).code());
    }

    @Test void ordinaryTextResponseRemainsACompleteStructuredString() {
        var stored = StoredModelMessage.tools(List.of(new ToolResponseMessage.ToolResponse("call-1", "lookup", "plain full result")));
        var projected = projection.project(context, "run", "event-1", 1, stored);
        assertEquals(MemoryJsonValue.Type.STRING, projected.toolResponses().get(0).responseData().type());
        assertEquals("plain full result", projected.toolResponses().get(0).responseData().scalar());
    }
}
