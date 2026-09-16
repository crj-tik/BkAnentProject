package com.bkanent.common.readiness;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers dependency readiness checks for every service that imports common.
 */
@AutoConfiguration
@EnableConfigurationProperties(ReadinessProperties.class)
public class ReadinessAutoConfiguration {

    @Bean(name = "dependencyReadiness")
    public DependencyReadinessHealthIndicator dependencyReadinessHealthIndicator(ReadinessProperties properties) {
        return new DependencyReadinessHealthIndicator(properties);
    }
}
