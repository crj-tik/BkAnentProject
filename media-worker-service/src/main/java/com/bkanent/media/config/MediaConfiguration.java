package com.bkanent.media.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 媒体 Worker 配置类。
 */
@Configuration
@EnableConfigurationProperties({MediaRocketMqProperties.class, MediaMinioProperties.class,
        MediaTaskProperties.class, MediaIntegrationProperties.class})
public class MediaConfiguration {

    private final MediaIntegrationProperties integrationProperties;
    private final String imageModel;

    public MediaConfiguration(MediaIntegrationProperties integrationProperties,
                              @Value("${media.integration.image-model:doubao-seedream-4.5-gen}") String imageModel) {
        this.integrationProperties = integrationProperties;
        this.imageModel = imageModel;
    }

    @jakarta.annotation.PostConstruct
    public void validateIntegrationMode() {
        if (integrationProperties.getMode() == null || integrationProperties.getMode().isBlank()
                || "unconfigured".equalsIgnoreCase(integrationProperties.getMode())) {
            throw new IllegalStateException("media.integration.mode must be explicitly set to local or real");
        }
        // real 模式已由 KE 网关 Seedream 文生图承接；模拟实现仍仅限 local（MockStableDiffusionClient 自行拦截）。
        if (!integrationProperties.isLocalMode() && !StringUtils.hasText(imageModel)) {
            throw new IllegalStateException("real media mode requires media.integration.image-model (KE gateway)");
        }
    }
}
