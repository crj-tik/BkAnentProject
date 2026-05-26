package com.bkanent.agent.stream;

import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Component
public class InMemorySessionEventBus implements SessionSubscriberRegistry {

    private final Map<String, Map<String, Consumer<SessionStreamEvent>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void register(String sessionId, String subscriberId, Consumer<SessionStreamEvent> consumer) {
        subscribers.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>()).put(subscriberId, consumer);
    }

    @Override
    public void unregister(String sessionId, String subscriberId) {
        Map<String, Consumer<SessionStreamEvent>> sessionSubscribers = subscribers.get(sessionId);
        if (sessionSubscribers == null) {
            return;
        }
        sessionSubscribers.remove(subscriberId);
        if (sessionSubscribers.isEmpty()) {
            subscribers.remove(sessionId);
        }
    }

    @Override
    public void publishLocal(SessionStreamEvent event) {
        Map<String, Consumer<SessionStreamEvent>> sessionSubscribers = subscribers.get(event.sessionId());
        if (sessionSubscribers == null || sessionSubscribers.isEmpty()) {
            return;
        }
        sessionSubscribers.values().forEach(consumer -> consumer.accept(event));
    }
}
