package com.bkanent.common.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStreamVisibilityTest {

    @Test
    void exposesOnlyExternalEventsForTheRequestedTaskAndItsChildren() {
        SessionStreamEvent child = new SessionStreamEvent("session", "child", "agent", "agent.delta", "text",
                Map.of(), "trace", 1L, "event-1", 1L, "child-run", "parent", null,
                "agent_execution", false, SessionStreamVisibility.PROGRESS);
        SessionStreamEvent internalChild = new SessionStreamEvent("session", "child", "agent", "agent.delta", "secret",
                Map.of(), "trace", 2L, "event-2", 2L, "child-run", "parent", null,
                "agent_execution", false, SessionStreamVisibility.INTERNAL);

        assertThat(SessionStreamVisibility.isVisibleToTask(child, "parent")).isTrue();
        assertThat(SessionStreamVisibility.isVisibleToTask(child, "child")).isTrue();
        assertThat(SessionStreamVisibility.isVisibleToTask(child, "another-parent")).isFalse();
        assertThat(SessionStreamVisibility.isVisibleToTask(internalChild, "parent")).isFalse();
        assertThat(SessionStreamVisibility.isVisibleToTask(child, null)).isTrue();
    }
}
