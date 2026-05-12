package com.bkanent.media.client;

/**
 * 生成媒体文件对象。
 */
public record GeneratedMediaFile(
        String fileName,
        byte[] content
) {
}
