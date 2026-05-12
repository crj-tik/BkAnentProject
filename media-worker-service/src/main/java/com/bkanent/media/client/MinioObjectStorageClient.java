package com.bkanent.media.client;

import com.bkanent.media.config.MediaMinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * MinIO 对象存储客户端实现。
 */
@Component
public class MinioObjectStorageClient implements MediaObjectStorageClient {

    private final MinioClient minioClient;
    private final MediaMinioProperties mediaMinioProperties;

    public MinioObjectStorageClient(MinioClient minioClient, MediaMinioProperties mediaMinioProperties) {
        this.minioClient = minioClient;
        this.mediaMinioProperties = mediaMinioProperties;
    }

    @PostConstruct
    public void initBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(mediaMinioProperties.getBucket())
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(mediaMinioProperties.getBucket())
                        .build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("初始化 MinIO Bucket 失败", exception);
        }
    }

    @Override
    public String upload(String objectPath, byte[] content) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(mediaMinioProperties.getBucket())
                    .object(objectPath)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType("image/png")
                    .build());
            return mediaMinioProperties.getPublicBaseUrl() + "/" + mediaMinioProperties.getBucket() + "/" + objectPath;
        } catch (Exception exception) {
            throw new IllegalStateException("上传生成文件到 MinIO 失败", exception);
        }
    }
}
