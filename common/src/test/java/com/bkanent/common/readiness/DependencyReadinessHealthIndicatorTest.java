package com.bkanent.common.readiness;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DependencyReadinessHealthIndicatorTest {

    @Test
    void localModeDoesNotRequireDistributedDependencies() {
        ReadinessProperties properties = new ReadinessProperties();
        properties.setMode("local");
        properties.setRequiredDependencies("database");

        Health health = new DependencyReadinessHealthIndicator(properties).health();

        assertEquals("UP", health.getStatus().getCode());
    }

    @Test
    void distributedModeReportsMissingDependencyEndpoint() {
        ReadinessProperties properties = new ReadinessProperties();
        properties.setMode("distributed");
        properties.setRequiredDependencies("database");

        Health health = new DependencyReadinessHealthIndicator(properties).health();

        assertEquals("DOWN", health.getStatus().getCode());
    }
}
