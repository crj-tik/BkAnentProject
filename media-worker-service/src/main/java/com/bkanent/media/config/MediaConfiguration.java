package com.bkanent.media.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 媒体 Worker 配置类。
 */
@Configuration
@EnableConfigurationProperties({MediaRocketMqProperties.class, MediaMinioProperties.class, MediaTaskProperties.class})
public class MediaConfiguration {
}
