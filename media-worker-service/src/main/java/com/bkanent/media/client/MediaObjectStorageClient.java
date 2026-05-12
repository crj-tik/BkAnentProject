package com.bkanent.media.client;

/**
 * 媒体对象存储客户端接口。
 */
public interface MediaObjectStorageClient {

    /**
     * 业务方法：upload。
     */
    String upload(String objectPath, byte[] content);
}
