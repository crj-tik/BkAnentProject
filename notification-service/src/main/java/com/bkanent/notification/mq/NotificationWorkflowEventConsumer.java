package com.bkanent.notification.mq;

import com.bkanent.common.agent.SessionStreamEvent;
import com.bkanent.notification.service.NotificationWorkflowEventService;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "notification.workflow-events.enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${notification.workflow-events.topic:bk.agent.session.stream}",
        consumerGroup = "${notification.workflow-events.consumer-group:notification-service-workflow-events}",
        selectorExpression = "approval || task_status || artifact || handoff || publish || notification",
        consumeMode = ConsumeMode.CONCURRENTLY
)
public class NotificationWorkflowEventConsumer implements RocketMQListener<SessionStreamEvent> {

    private final NotificationWorkflowEventService notificationWorkflowEventService;

    public NotificationWorkflowEventConsumer(NotificationWorkflowEventService notificationWorkflowEventService) {
        this.notificationWorkflowEventService = notificationWorkflowEventService;
    }

    @Override
    public void onMessage(SessionStreamEvent event) {
        notificationWorkflowEventService.consume(event);
    }
}
