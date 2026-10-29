package com.bkanent.knowledge.a2a;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSupervisorContextTest {
    @Test
    void dropsFreeTextAndStructuredContextBeforeItReachesTheModel() {
        Map<String, Object> context = Map.of(
                "threadId", "task-1",
                "supervisor", Map.of(
                        "domain", "knowledge",
                        "taskId", "task-2",
                        "intent", "哨兵逐字稿原文",
                        "expectedOutput", "哨兵逐字稿原文",
                        "constraints", List.of("哨兵逐字稿原文"),
                        "structuredContext", Map.of("transcript", "哨兵逐字稿原文")));

        Map<String, Object> safe = A2aSupervisorContextInterceptor.safeContext(context);
        String rendered = com.bkanent.common.a2a.A2aSupervisorContextRenderer.render(safe);

        assertThat(rendered).contains("domain=knowledge").contains("taskId=task-2");
        assertThat(rendered).doesNotContain("哨兵逐字稿原文").doesNotContain("structuredContext")
                .doesNotContain("expectedOutput").doesNotContain("constraints");
    }
}
