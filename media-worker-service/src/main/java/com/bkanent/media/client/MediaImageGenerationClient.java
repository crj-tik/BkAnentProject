package com.bkanent.media.client;

import java.util.List;

/**
 * 图片生成客户端接口。
 */
public interface MediaImageGenerationClient {

    /**
     * 业务方法：generateListingImages。
     */
    List<GeneratedMediaFile> generateListingImages(Long listingId, String prompt, List<String> angles);
}
