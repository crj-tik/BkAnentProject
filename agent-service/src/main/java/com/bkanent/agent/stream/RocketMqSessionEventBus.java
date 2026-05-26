package com.bkanent.agent.stream;

import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.common.agent.SessionEventTags;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Primary
@Component
@ConditionalOnProperty(name = "agent.distributed.stream.provider", havingValue = "rocketmq")
@RocketMQMessageListener(
        topic = "${agent.distributed.stream.rocketmq.topic:bk.agent.session.stream}",
        consumerGroup = "${agent.distributed.stream.rocketmq.consumer-group:agent-service-session-stream}",
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class RocketMqSessionEventBus implements SessionEventBus, RocketMQListener<SessionStreamEvent> {

    private final RocketMQTemplate rocketMQTemplate;
    private final SessionSubscriberRegistry subscriberRegistry;
    private final SessionEventAuditService sessionEventAuditService;
    private final String topic;

    public RocketMqSessionEventBus(RocketMQTemplate rocketMQTemplate,
                                   SessionSubscriberRegistry subscriberRegistry,
                                   SessionEventAuditService sessionEventAuditService,
                                   @Value("${agent.distributed.stream.rocketmq.topic:bk.agent.session.stream}") String topic) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.subscriberRegistry = subscriberRegistry;
        this.sessionEventAuditService = sessionEventAuditService;
        this.topic = topic;
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
        rocketMQTemplate.convertAndSend(topic + ":" + SessionEventTags.resolveTag(event.eventType()), event);
    }

    @Override
    public void onMessage(SessionStreamEvent event) {
        subscriberRegistry.publishLocal(event);
    }

}
