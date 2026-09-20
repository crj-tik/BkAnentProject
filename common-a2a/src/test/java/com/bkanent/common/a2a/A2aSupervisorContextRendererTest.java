package com.bkanent.common.a2a;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A2aSupervisorContextRendererTest {

    @Test
    void rendersWhitelistedStructuredSupervisorFields() {
        String rendered = A2aSupervisorContextRenderer.render(Map.of(
                "threadId", "thread-1",
                "supervisor", Map.of(
                        "traceId", "trace-1",
                        "structuredContext", Map.of("keyword", "浦东"),
                        "secretPrompt", "must-not-render")));

        assertThat(rendered).contains("threadId=thread-1", "supervisor.traceId=trace-1", "keyword")
                .doesNotContain("must-not-render", "secretPrompt");
    }
}
