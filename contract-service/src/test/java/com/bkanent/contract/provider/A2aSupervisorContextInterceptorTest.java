package com.bkanent.contract.provider;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.bkanent.contract.a2a.A2aSupervisorContextInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class A2aSupervisorContextInterceptorTest {

    @Test
    void mapsOfficialA2aMetadataIntoModelSystemContext() {
        ModelRequest request = ModelRequest.builder()
                .messages(List.of(new UserMessage("review this contract")))
                .context(Map.of(
                        "threadId", "thread-1",
                        "isStreaming", true,
                        "supervisor", Map.of(
                                "taskId", "task-1",
                                "traceId", "trace-1",
                                "constraints", List.of("只返回风险摘要"),
                                "structuredContext", Map.of("contractId", "C-1")
                        )))
                .build();
        AtomicReference<ModelRequest> captured = new AtomicReference<>();

        new A2aSupervisorContextInterceptor().interceptModel(
                request,
                enriched -> {
                    captured.set(enriched);
                    return new com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse("ok");
                });

        assertThat(captured.get().getSystemMessage().getText())
                .contains("threadId=thread-1")
                .contains("isStreaming=true")
                .contains("taskId=task-1")
                .contains("contractId=C-1")
                .contains("只返回风险摘要");
    }
}
