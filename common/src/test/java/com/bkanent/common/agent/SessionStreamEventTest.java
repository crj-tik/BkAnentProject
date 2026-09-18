package com.bkanent.common.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionStreamEventTest {

    @Test
    void keepsLegacyEightArgumentConstructionCompatible() {
        SessionStreamEvent event = new SessionStreamEvent(
                "session", "task", "agent", "agent.delta", "chunk", Map.of(), "trace", 1L);

        assertThat(event.eventId()).isNull();
        assertThat(event.sequence()).isNull();
        assertThat(event.childRunId()).isNull();
        assertThat(event.visibility()).isNull();
    }

    @Test
    void identifiesTerminalAndAllowedLifecycleEvents() {
        assertThat(SessionStreamEventTypes.isAllowed("agent.delta")).isTrue();
        assertThat(SessionStreamEventTypes.isAllowed("workflow.completed")).isTrue();
        assertThat(SessionStreamEventTypes.isTerminal("agent.completed")).isTrue();
        assertThat(SessionStreamEventTypes.isTerminal("agent.delta")).isFalse();
    }
}
