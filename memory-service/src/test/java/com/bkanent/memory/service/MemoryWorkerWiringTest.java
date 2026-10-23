package com.bkanent.memory.service;

import com.bkanent.memory.config.MemoryExperienceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class MemoryWorkerWiringTest {
    @Test
    void requiredCleanupAndEnabledExperienceWorkersUseTheirProductionConstructors() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "memory.v2.experience.enabled", "true",
                    "memory.v2.experience.processing-enabled", "true")));
            context.registerBean(MemoryArchiveStore.class, () -> mock(MemoryArchiveStore.class));
            context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
            context.registerBean(MemoryExperienceService.class, () -> mock(MemoryExperienceService.class));
            context.registerBean(MemoryExperienceProperties.class, MemoryExperienceProperties::new);
            context.registerBean(MemoryPrivacyCleanupWorker.class);
            context.registerBean(MemoryExperienceProcessingWorker.class);
            context.refresh();

            assertNotNull(context.getBean(MemoryPrivacyCleanupWorker.class));
            assertNotNull(context.getBean(MemoryExperienceProcessingWorker.class));
        }
    }
}
