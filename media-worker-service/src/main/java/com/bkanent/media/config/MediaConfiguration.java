package com.bkanent.media.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 媒体 Worker 配置类。
 */
@Configuration
@EnableConfigurationProperties({MediaRocketMqProperties.class, MediaMinioProperties.class,
        MediaTaskProperties.class, MediaIntegrationProperties.class})
public class MediaConfiguration {

    private final MediaIntegrationProperties integrationProperties;

    public MediaConfiguration(MediaIntegrationProperties integrationProperties) {
        this.integrationProperties = integrationProperties;
    }

    @jakarta.annotation.PostConstruct
    public void validateIntegrationMode() {
        if (integrationProperties.getMode() == null || integrationProperties.getMode().isBlank()
                || "unconfigured".equalsIgnoreCase(integrationProperties.getMode())) {
            throw new IllegalStateException("media.integration.mode must be explicitly set to local or real");
        }
        if (!integrationProperties.isLocalMode()) {
            throw new IllegalStateException("real media generation provider is not implemented; simulated provider is local-only");
        }
    }
}
