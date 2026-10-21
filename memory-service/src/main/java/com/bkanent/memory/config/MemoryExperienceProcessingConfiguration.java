package com.bkanent.memory.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "memory.v2.experience", name = {"enabled", "processing-enabled"}, havingValue = "true")
@ConditionalOnProperty(prefix = "memory.v2", name = "archive-enabled", havingValue = "true")
public class MemoryExperienceProcessingConfiguration {
    @Bean
    ThreadPoolTaskScheduler memoryExperienceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("experience-processing-");
        return scheduler;
    }
}
