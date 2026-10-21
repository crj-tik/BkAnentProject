package com.bkanent.agent.stream;

import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.agent.SessionStreamVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InMemorySessionStreamServiceTest {

    @Test
    void parentSubscriptionReplaysAndStreamsChildEventsOnceWithVisibilityFiltering() {
        InMemorySessionEventBus bus = new InMemorySessionEventBus();
        SessionEventAuditService audit = mock(SessionEventAuditService.class);
        RecordingEmitter emitter = new RecordingEmitter();
        SessionStreamEvent replayedChild = event("child-1", "parent-1", 1L, "progress");
        when(audit.sequenceForEventId("session-1", null)).thenReturn(0L);
        when(audit.replay(org.mockito.ArgumentMatchers.eq("session-1"), org.mockito.ArgumentMatchers.eq("parent-1"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> invocation.<Long>getArgument(3) == 0L ? List.of(replayedChild) : List.of());
        when(audit.recordAndEnrich(any())).thenAnswer(invocation -> invocation.getArgument(0));
        InMemorySessionStreamService service = new InMemorySessionStreamService(new LocalSessionEventBus(bus), bus, audit) {
            @Override SseEmitter createEmitter() { return emitter; }
        };

        service.subscribe("session-1", "parent-1", null, null);
        service.publish(event("child-1", "parent-1", 1L, "progress")); // replay/live overlap
        service.publish(event("child-1", "parent-1", 2L, "progress"));
        service.publish(event("other-child", "other-parent", 3L, "progress"));
        service.publish(event("internal-child", "parent-1", 4L, "internal"));

        assertThat(emitter.events).hasSize(2);
        emitter.complete();
    }

    private SessionStreamEvent event(String taskId, String parentTaskId, long sequence, String visibility) {
        return new SessionStreamEvent("session-1", taskId, "agent", "agent.delta", "chunk", Map.of(), "trace",
                sequence, "event-" + sequence, sequence, "child-run", parentTaskId, null,
                "agent_execution", false, visibility);
    }

    private static final class RecordingEmitter extends SseEmitter {
        private final List<SseEventBuilder> events = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            events.add(builder);
        }
    }
}
