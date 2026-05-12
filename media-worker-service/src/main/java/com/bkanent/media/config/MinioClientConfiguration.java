package com.bkanent.media.config;

import io.minio.MinioClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置。
 */
@Configuration
public class MinioClientConfiguration {

    @Bean
    public MinioClient minioClient(MediaMinioProperties mediaMinioProperties) {
        return MinioClient.builder()
                .endpoint(mediaMinioProperties.getEndpoint())
                .credentials(mediaMinioProperties.getAccessKey(), mediaMinioProperties.getSecretKey())
                .build();
    }
}
