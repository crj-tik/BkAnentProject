package com.bkanent.agent.stream;

import com.bkanent.common.agent.SessionStreamEvent;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RocketMqSessionEventBusTest {

    @Test
    void broadcastsEachStreamNotificationToEveryAgentInstance() {
        RocketMQMessageListener listener = RocketMqSessionEventBus.class.getAnnotation(RocketMQMessageListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.messageModel()).isEqualTo(MessageModel.BROADCASTING);
    }

    @Test
    void forwardsConsumedNotificationToLocalSseSubscribers() {
        SessionSubscriberRegistry subscriberRegistry = mock(SessionSubscriberRegistry.class);
        SessionEventAuditService auditService = mock(SessionEventAuditService.class);
        RocketMqSessionEventBus eventBus = new RocketMqSessionEventBus(
                mock(RocketMQTemplate.class), subscriberRegistry, auditService, "session-stream");
        SessionStreamEvent event = new SessionStreamEvent(
                "session", "task", "agent", "agent.delta", "delta", Map.of(), "trace", 1L,
                "event-1", 1L, null, null, null, "agent_execution", false, "progress");

        eventBus.onMessage(event);

        verify(subscriberRegistry).publishLocal(event);
    }

    @Test
    void deliversLocallyBeforePublishingCrossInstanceNotification() {
        SessionSubscriberRegistry subscriberRegistry = mock(SessionSubscriberRegistry.class);
        RocketMQTemplate rocketMQTemplate = mock(RocketMQTemplate.class);
        RocketMqSessionEventBus eventBus = new RocketMqSessionEventBus(
                rocketMQTemplate, subscriberRegistry, mock(SessionEventAuditService.class), "session-stream");
        SessionStreamEvent event = new SessionStreamEvent(
                "session", "task", "agent", "agent.delta", "delta", Map.of(), "trace", 1L,
                "event-2", 2L, null, null, null, "agent_execution", false, "progress");

        eventBus.publish(event);

        verify(subscriberRegistry).publishLocal(event);
        verify(rocketMQTemplate).convertAndSend(anyString(), eq(event));
    }
}
