package com.bkanent.memory.config;

import com.bkanent.memory.service.PreferenceEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class MemoryPreferenceProcessingConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(MemoryPreferenceProcessingConfiguration.class)
            .withBean(ObjectMapper.class,ObjectMapper::new);
    @Test void defaultOffAndArchiveOffNeedNoModelOrCredentials() {
        runner.run(context -> assertThat(context).doesNotHaveBean(PreferenceEvaluator.class));
        runner.withPropertyValues("memory.v2.preference.processing.enabled=true").run(context -> assertThat(context).doesNotHaveBean(PreferenceEvaluator.class));
    }
    @Test void explicitEnableBuildsOneToolFreeModelWithoutNetworkAtStartup() {
        runner.withPropertyValues("memory.v2.archive-enabled=true","memory.v2.preference.processing.enabled=true",
                "memory.v2.preference.processing.model=fixture-model","DEEPSEEK_API_KEY=synthetic-fixture-key")
                .run(context -> { assertThat(context).hasSingleBean(PreferenceEvaluator.class); assertThat(context).hasBean("memoryPreferenceScheduler"); });
    }
    @Test void capacityConfigurationHasFiniteLimits() {
        var properties=new MemoryPreferenceProcessingProperties(); assertFalse(properties.isEnabled());
        assertEquals(MemoryPreferenceProcessingProperties.Mode.SHADOW,properties.getMode());
        assertThrows(IllegalArgumentException.class,() -> properties.setBatchSize(100));
        assertThrows(IllegalArgumentException.class,() -> properties.setMaxAttempts(0));
        assertThrows(IllegalArgumentException.class,() -> properties.setLeaseSeconds(1));
    }
}
