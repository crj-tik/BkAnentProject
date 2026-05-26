package com.bkanent.notification.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class NotificationMetricsService {

    private final MeterRegistry meterRegistry;

    public NotificationMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordWorkflowEvent(String eventType, String status, long durationMs) {
        Timer.builder("bk.notification.workflow.event.duration")
                .tag("eventType", normalize(eventType))
                .tag("status", normalize(status))
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        Counter.builder("bk.notification.workflow.event.count")
                .tag("eventType", normalize(eventType))
                .tag("status", normalize(status))
                .register(meterRegistry)
                .increment();
    }

    public void recordConsumeResult(String status) {
        Counter.builder("bk.notification.workflow.consume.count")
                .tag("status", normalize(status))
                .register(meterRegistry)
                .increment();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
