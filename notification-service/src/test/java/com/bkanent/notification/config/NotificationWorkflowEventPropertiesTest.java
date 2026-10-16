package com.bkanent.notification.config;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** RocketMQ rejects topic names outside ^[%|a-zA-Z0-9_-]+$ (KI-123). */
class NotificationWorkflowEventPropertiesTest {

    @Test
    void defaultWorkflowTopicIsRocketMqLegal() {
        assertThat(new NotificationWorkflowEventProperties().getTopic())
                .matches(Pattern.compile("^[%|a-zA-Z0-9_-]+$"));
    }
}
