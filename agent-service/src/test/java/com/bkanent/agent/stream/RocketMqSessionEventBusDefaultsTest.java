package com.bkanent.agent.stream;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RocketMQ rejects topic names outside ^[%|a-zA-Z0-9_-]+$, so every repository
 * default for the session-stream topic must stay free of dots. A dotted default
 * makes each child-agent event publish throw and turns every supervisor child
 * call into OUTCOME_UNKNOWN (KI-123).
 */
class RocketMqSessionEventBusDefaultsTest {

    private static final Pattern LEGAL_TOPIC = Pattern.compile("^[%|a-zA-Z0-9_-]+$");
    private static final String TOPIC_PROPERTY_PREFIX = "${agent.distributed.stream.rocketmq.topic:";

    @Test
    void annotationAndValueTopicDefaultsAreRocketMqLegalAndIdentical() throws Exception {
        RocketMQMessageListener listener = RocketMqSessionEventBus.class.getAnnotation(RocketMQMessageListener.class);
        String annotationDefault = defaultAfter(listener.topic(), "annotation");
        String valueDefault = constructorValueDefault();
        assertThat(annotationDefault).matches(LEGAL_TOPIC);
        assertThat(valueDefault).matches(LEGAL_TOPIC);
        assertThat(annotationDefault).isEqualTo(valueDefault);
    }

    @Test
    void nacosTemplateTopicDefaultsAreRocketMqLegal() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String module : List.of("agent-service", "notification-service")) {
            Path template = Path.of("..", "nacos", module + ".yaml");
            assertThat(template).as("nacos template next to the reactor").exists();
            for (String line : Files.readAllLines(template, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("topic: ${AGENT_STREAM_ROCKETMQ_TOPIC:")) {
                    String defaultName = defaultAfter(trimmed.substring("topic: ".length()), template.toString());
                    if (!LEGAL_TOPIC.matcher(defaultName).matches()) {
                        offenders.add(template + " -> " + defaultName);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private String constructorValueDefault() throws Exception {
        for (Constructor<?> constructor : RocketMqSessionEventBus.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                Value value = parameter.getAnnotation(Value.class);
                if (value != null && value.value().startsWith(TOPIC_PROPERTY_PREFIX)) {
                    return defaultAfter(value.value(), "@Value");
                }
            }
        }
        throw new IllegalStateException("constructor topic @Value not found");
    }

    private String defaultAfter(String placeholder, String origin) {
        int start = placeholder.lastIndexOf(':');
        assertThat(start).as("placeholder default in %s: %s", origin, placeholder).isGreaterThan(0);
        return placeholder.substring(start + 1, placeholder.length() - 1);
    }
}
