package com.bkanent.common.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.spec.DataPart;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendConfiguration;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aInputParserTest {

    private final A2aInputParser parser = new A2aInputParser(new ObjectMapper());
    private final A2aOutputPolicy policy = new A2aOutputPolicy("listing", true, 200, 500);

    @Test
    void combinesTextAndDataPartsAndKeepsSupervisorMetadata() {
        RequestContext context = context(
                List.of(new TextPart("查询房源"), new DataPart(Map.of("keyword", "浦东", "topK", 5))),
                Map.of("threadId", "thread-1", "isStreaming", true,
                        "supervisor", Map.of("traceId", "trace-1")),
                List.of("text", "application/json"));

        A2aInput input = parser.parse(context, policy);

        assertThat(input.instruction()).contains("查询房源", "Structured A2A input", "浦东");
        assertThat(input.structuredContext()).containsEntry("keyword", "浦东")
                .containsEntry("topK", 5);
        assertThat(input.metadata()).containsEntry("threadId", "thread-1")
                .containsKey("supervisor");
        assertThat(input.jsonOutput()).isTrue();
    }

    @Test
    void acceptsTextOnlyOutputWhenThatIsTheExplicitMode() {
        A2aInput input = parser.parse(context(List.of(new TextPart("生成摘要")), Map.of(), List.of("text")), policy);

        assertThat(input.instruction()).isEqualTo("生成摘要");
        assertThat(input.jsonOutput()).isFalse();
    }

    @Test
    void rejectsOversizedInput() {
        RequestContext context = context(List.of(new TextPart("x".repeat(201))), Map.of(), List.of("text"));

        assertThatThrownBy(() -> parser.parse(context, policy))
                .isInstanceOf(A2aInputException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void ignoresNullStructuredValues() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", "value");
        data.put("ignored", null);
        RequestContext context = context(List.of(new DataPart(data)), Map.of(), List.of("text"));

        A2aInput input = parser.parse(context, policy);

        assertThat(input.instruction()).contains("valid", "value");
        assertThat(input.structuredContext()).containsEntry("valid", "value")
                .doesNotContainKey("ignored");
    }

    @Test
    void acceptsAnEmptyMessageAndLeavesInstructionBlank() {
        A2aInput input = parser.parse(context(List.of(new TextPart("")), Map.of(), List.of("text")), policy);

        assertThat(input.instruction()).isEmpty();
        assertThat(input.structuredContext()).isEmpty();
    }

    private RequestContext context(List<io.a2a.spec.Part<?>> parts,
                                   Map<String, Object> metadata,
                                   List<String> acceptedOutputModes) {
        Message message = new Message.Builder()
                .role(Message.Role.USER)
                .parts(parts)
                .taskId("task-1")
                .contextId("context-1")
                .build();
        MessageSendParams params = new MessageSendParams(
                message,
                new MessageSendConfiguration(acceptedOutputModes, null, null, true),
                metadata);
        return new RequestContext.Builder()
                .setParams(params)
                .setTaskId("task-1")
                .setContextId("context-1")
                .build();
    }
}
