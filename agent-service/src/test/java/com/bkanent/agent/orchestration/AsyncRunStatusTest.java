package com.bkanent.agent.orchestration;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AsyncRunStatusTest {
    @Test
    void waitingStopsPollingWithoutDeclaringTerminalAndBothCancelSpellingsAreTerminal() {
        for (String status : java.util.List.of("WAITING_USER_INPUT", "WAITING_USER_APPROVAL")) {
            assertThat(AsyncRunStatus.stopStatusStream(status)).isTrue();
            assertThat(AsyncRunStatus.terminal(status)).isFalse();
        }
        assertThat(AsyncRunStatus.stopStatusStream("RUNNING")).isFalse();
        assertThat(AsyncRunStatus.terminal("CANCELED")).isTrue();
        assertThat(AsyncRunStatus.terminal("CANCELLED")).isTrue();
    }
}
