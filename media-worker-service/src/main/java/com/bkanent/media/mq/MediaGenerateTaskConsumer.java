package com.bkanent.media.mq;

import com.bkanent.common.model.MediaTaskResultDTO;
import com.bkanent.common.rpc.MarketingContentRpcService;
import com.bkanent.media.client.GeneratedMediaFile;
import com.bkanent.media.client.MediaImageGenerationClient;
import com.bkanent.media.client.MediaObjectStorageClient;
import com.bkanent.media.config.MediaMinioProperties;
import com.bkanent.media.model.MediaGenerateTaskMessage;
import com.bkanent.media.service.MediaTaskStoreService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 媒体生成任务 RocketMQ 消费者。
 */
@Component
@RocketMQMessageListener(
        topic = "${media.rocketmq.generate-topic:media_generate_task}",
        consumerGroup = "${media.rocketmq.consumer-group:media-worker-consumer-group}"
)
public class MediaGenerateTaskConsumer implements RocketMQListener<MediaGenerateTaskMessage> {

    private static final Logger log = LoggerFactory.getLogger(MediaGenerateTaskConsumer.class);

    @DubboReference(check = false)
    private MarketingContentRpcService marketingContentRpcService;

    private final MediaImageGenerationClient mediaImageGenerationClient;
    private final MediaObjectStorageClient mediaObjectStorageClient;
    private final MediaTaskStoreService mediaTaskStoreService;
    private final MediaMinioProperties mediaMinioProperties;

    public MediaGenerateTaskConsumer(MediaImageGenerationClient mediaImageGenerationClient,
                                     MediaObjectStorageClient mediaObjectStorageClient,
                                     MediaTaskStoreService mediaTaskStoreService,
                                     MediaMinioProperties mediaMinioProperties) {
        this.mediaImageGenerationClient = mediaImageGenerationClient;
        this.mediaObjectStorageClient = mediaObjectStorageClient;
        this.mediaTaskStoreService = mediaTaskStoreService;
        this.mediaMinioProperties = mediaMinioProperties;
    }

    @Override
    public void onMessage(MediaGenerateTaskMessage message) {
        mediaTaskStoreService.markRunning(message.taskId());
        try {
            List<GeneratedMediaFile> files = mediaImageGenerationClient.generateListingImages(
                    message.request().listingId(),
                    message.request().prompt(),
                    message.request().angles()
            );
            List<String> assetUrls = new ArrayList<>();
            for (GeneratedMediaFile file : files) {
                String objectPath = message.request().listingId() + "/" + message.taskId() + "/" + file.fileName();
                assetUrls.add(mediaObjectStorageClient.upload(objectPath, file.content()));
            }
            String coverImageUrl = assetUrls.isEmpty() ? null : assetUrls.get(0);
            MediaTaskResultDTO result = mediaTaskStoreService.markSuccess(
                    message.taskId(),
                    assetUrls,
                    coverImageUrl,
                    null,
                    "媒体素材生成完成并已上传到 MinIO"
            );
            callbackResult(result);
            log.info("RocketMQ 媒体任务消费完成，taskId={}，assetCount={}，bucket={}",
                    message.taskId(), assetUrls.size(), mediaMinioProperties.getBucket());
        } catch (Exception exception) {
            mediaTaskStoreService.markFailed(message.taskId(), "媒体素材生成失败: " + exception.getMessage());
            log.error("RocketMQ 媒体任务消费失败，taskId={}", message.taskId(), exception);
            throw new IllegalStateException("媒体任务消费失败", exception);
        }
    }

    private void callbackResult(MediaTaskResultDTO result) {
        if (result.contentId() != null && marketingContentRpcService != null) {
            marketingContentRpcService.bindGeneratedAssets(
                    result.contentId(),
                    result.assetUrls(),
                    result.coverImageUrl(),
                    result.videoUrl(),
                    "媒体 Worker 已通过 RocketMQ 消费并回写生成素材"
            );
        }
    }
}
