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
    private final SessionEventAuditService sessionEventAuditService;

    public LocalSessionEventBus(SessionSubscriberRegistry subscriberRegistry,
                                SessionEventAuditService sessionEventAuditService) {
        this.subscriberRegistry = subscriberRegistry;
        this.sessionEventAuditService = sessionEventAuditService;
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
        sessionEventAuditService.record(event);
        subscriberRegistry.publishLocal(event);
    }
}
