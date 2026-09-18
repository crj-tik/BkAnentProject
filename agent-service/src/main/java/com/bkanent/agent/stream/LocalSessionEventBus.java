package com.bkanent.agent.stream;

import com.bkanent.common.agent.SessionStreamEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Primary
@Component
@ConditionalOnProperty(name = "agent.distributed.stream.provider", havingValue = "memory", matchIfMissing = true)
public class LocalSessionEventBus implements SessionEventBus {

    private final SessionSubscriberRegistry subscriberRegistry;

    public LocalSessionEventBus(SessionSubscriberRegistry subscriberRegistry) {
        this.subscriberRegistry = subscriberRegistry;
    }

    @Override
    public void register(String sessionId, String subscriberId, Consumer<SessionStreamEvent> consumer) {
        subscriberRegistry.register(sessionId, subscriberId, consumer);
    }

    @Override
    public void unregister(String sessionId, String subscriberId) {
        subscriberRegistry.unregister(sessionId, subscriberId);
    }

    @Override
    public void publish(SessionStreamEvent event) {
        subscriberRegistry.publishLocal(event);
    }
}
