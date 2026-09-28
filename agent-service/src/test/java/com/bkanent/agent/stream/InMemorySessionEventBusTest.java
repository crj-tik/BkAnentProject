package com.bkanent.agent.stream;

import com.bkanent.common.agent.SessionStreamEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySessionEventBusTest {

    @Test
    void oneBrokenLocalSubscriberDoesNotPreventOtherSubscribersFromReceivingEvent() {
        InMemorySessionEventBus eventBus = new InMemorySessionEventBus();
        AtomicBoolean received = new AtomicBoolean();
        SessionStreamEvent event = new SessionStreamEvent(
                "session", "task", "agent", "agent.delta", "delta", Map.of(), "trace", 1L);
        eventBus.register("session", "broken", ignored -> {
            throw new IllegalStateException("subscriber disconnected");
        });
        eventBus.register("session", "healthy", ignored -> received.set(true));

        eventBus.publishLocal(event);

        assertThat(received).isTrue();
    }
}
