package com.bkanent.agent.stream;

import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

@Service
public class InMemorySessionStreamService implements SessionStreamService {

    private final SessionEventBus sessionEventBus;
    private final SessionSubscriberRegistry subscriberRegistry;

    public InMemorySessionStreamService(SessionEventBus sessionEventBus,
                                        SessionSubscriberRegistry subscriberRegistry) {
        this.sessionEventBus = sessionEventBus;
        this.subscriberRegistry = subscriberRegistry;
    }

    @Override
    public SseEmitter subscribe(String sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        String subscriberId = UUID.randomUUID().toString();
        subscriberRegistry.register(sessionId, subscriberId, event -> sendEvent(sessionId, subscriberId, emitter, event));
        emitter.onCompletion(() -> subscriberRegistry.unregister(sessionId, subscriberId));
        emitter.onTimeout(() -> subscriberRegistry.unregister(sessionId, subscriberId));
        emitter.onError(throwable -> subscriberRegistry.unregister(sessionId, subscriberId));
        return emitter;
    }

    @Override
    public void publish(SessionStreamEvent event) {
        sessionEventBus.publish(event);
    }

    private void sendEvent(String sessionId,
                           String subscriberId,
                           SseEmitter emitter,
                           SessionStreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.eventType())
                    .data(event));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
            subscriberRegistry.unregister(sessionId, subscriberId);
        }
    }
}
